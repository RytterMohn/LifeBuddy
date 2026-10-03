// llama.cpp JNI 绑定面（真实实现）
//
// 对应 Kotlin 绑定：core/.../llm/LlamaCppEngine.kt 的 NativeLlama 对象
//   llamaInit → llamaLoadModel → llamaGenerate（流式）→ llamaRelease
//
// 桌面调试构建：
//   ./native/build_desktop.sh      # 产物 native/libllama.dylib（macOS）
//   java 侧 System.loadLibrary("llama") 自动寻找 libllama.dylib
//
// Android 构建（NDK）：
//   ./native/build_android.sh      # 产物 app/src/main/jniLibs/arm64-v8a/libllama.so
//
// 实现要点：
//   - 生成循环：tokenize(add_special+parse_special) → 分块 decode prompt
//     → 采样（temp + top_p + dist）→ token_to_piece → UTF-8 缓存对齐后回调 Kotlin
//   - EOG 检测（llama_vocab_is_eog）覆盖 Gemma 3 的 <end_of_turn> 等结束符
//   - 回调在调用线程同步执行（Kotlin 侧 Dispatchers.IO 保证串行，无跨线程问题）
//   - prompt 最后一个 token 置 logits=1，供首次采样读取
//
#include <jni.h>
#include <llama.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <thread>
#include <ctime>
#include <algorithm>
#include <random>
#include <atomic>

#if defined(__ANDROID__)
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "llama-jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llama-jni", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, "[llama-jni] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, "[llama-jni] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

static JavaVM* g_jvm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

struct LlamaState {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_batch batch{};
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool> cancelled{false};
};

static constexpr int32_t BATCH_SIZE = 512;

// ---------- 小工具 ----------

static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string s = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

// 校验 UTF-8 完整性：多字节字符可能被拆到两个 token，缓存到合法后再回调
static bool is_valid_utf8(const char* string) {
    if (!string) return true;
    const auto* bytes = (const unsigned char*)string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

static void batch_clear(llama_batch& batch) { batch.n_tokens = 0; }

static void batch_add(llama_batch& batch, llama_token token, llama_pos pos, bool logits) {
    const int32_t i = batch.n_tokens++;
    batch.token[i] = token;
    batch.pos[i] = pos;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = logits ? 1 : 0;
}

// 回调 Kotlin 的 NativeTokenCallback.onToken(String)
static void invoke_callback(JNIEnv* env, jobject callback, jmethodID onToken, const std::string& piece) {
    if (!callback || !onToken) return;
    jstring jpiece = env->NewStringUTF(piece.c_str());
    if (jpiece) {
        env->CallVoidMethod(callback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);
    }
}

// ---------- JNI 导出 ----------

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_ondevice_gemma_llm_NativeLlama_llamaInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    llama_backend_init();
    auto* state = new LlamaState();
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jboolean JNICALL
Java_dev_ondevice_gemma_llm_NativeLlama_llamaLoadModel(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring modelPath, jint nCtx) {
    auto* state = reinterpret_cast<LlamaState*>(handle);
    if (!state) return JNI_FALSE;

    const std::string path = jstring_to_string(env, modelPath);
    LOGI("loading model: %s", path.c_str());

    llama_model_params mparams = llama_model_default_params();
    // 0 = 纯 CPU；Android 有 Vulkan 后端时可调大做 GPU offload
    mparams.n_gpu_layers = 0;
    state->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!state->model) { LOGE("load model failed: %s", path.c_str()); return JNI_FALSE; }

    state->vocab = llama_model_get_vocab(state->model);
    if (!state->vocab) { LOGE("no vocab"); return JNI_FALSE; }

    const int32_t n_ctx_train = llama_model_n_ctx_train(state->model);
    const int32_t n_ctx = std::min((int32_t)nCtx, n_ctx_train);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    const unsigned int n_cores = std::max(1u, std::thread::hardware_concurrency());
    cparams.n_threads = std::min(n_cores, 8u);
    cparams.n_threads_batch = std::min(n_cores, 8u);
    state->ctx = llama_init_from_model(state->model, cparams);
    if (!state->ctx) { LOGE("init context failed"); return JNI_FALSE; }

    state->batch = llama_batch_init(BATCH_SIZE, 0, 1);
    state->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(state->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_dist(0));

    LOGI("model ready: ctx=%d train_ctx=%d", n_ctx, n_ctx_train);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_dev_ondevice_gemma_llm_NativeLlama_llamaGenerate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jprompt,
    jfloat temperature, jfloat topP, jint maxTokens, jobject callback) {
    auto* state = reinterpret_cast<LlamaState*>(handle);
    if (!state || !state->ctx) return env->NewStringUTF("");

    state->cancelled.store(false);
    const std::string prompt = jstring_to_string(env, jprompt);

    // 采样参数随调用更新（链上已有默认值，这里重建更直接）
    llama_sampler_free(state->sampler);
    state->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(state->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(state->sampler, llama_sampler_init_dist((uint32_t)time(nullptr)));

    // 1. tokenize：parse_special 保留 <start_of_turn> 等特殊 token
    std::vector<llama_token> tokens(llama_vocab_n_tokens(state->vocab));
    const int32_t n_tokens = llama_tokenize(
        state->vocab, prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), (int32_t)tokens.size(), /*add_special*/ true, /*parse_special*/ true);
    if (n_tokens < 0) { LOGE("tokenize failed"); return env->NewStringUTF(""); }
    tokens.resize(n_tokens);
    LOGI("prompt tokens: %d", n_tokens);

    // 回调方法 ID 解析一次（SAM 实现类上有 onToken）
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = cbClass ? env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (cbClass) env->DeleteLocalRef(cbClass);
    if (!onToken) { LOGE("onToken method not found"); return env->NewStringUTF(""); }

    // The caller supplies the entire history. Start a fresh cache instead of appending it twice.
    llama_memory_clear(llama_get_memory(state->ctx), false);
    llama_pos n_past = 0;
    const int32_t n_ctx = llama_n_ctx(state->ctx);
    if (maxTokens < 1 || n_tokens + maxTokens > n_ctx) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(error, "Prompt plus output budget exceeds model context; start a shorter conversation");
        return nullptr;
    }

    // 2. 分块 decode prompt（最后一块最后一个 token 置 logits=1）
    for (int32_t i = 0; i < n_tokens; i += BATCH_SIZE) {
        if (state->cancelled.load()) return env->NewStringUTF("");
        const int32_t chunk = std::min(BATCH_SIZE, n_tokens - i);
        batch_clear(state->batch);
        for (int32_t j = 0; j < chunk; ++j) {
            const bool want_logits = (i + j == n_tokens - 1);
            batch_add(state->batch, tokens[i + j], n_past++, want_logits);
        }
        if (llama_decode(state->ctx, state->batch) != 0) {
            LOGE("llama_decode failed on prompt chunk");
            return env->NewStringUTF("");
        }
    }

    // 3. 生成循环
    std::string full_text;
    std::string cached; // 未凑齐的 UTF-8 字节缓存
    char piece_buf[512];

    for (int32_t n = 0; n < maxTokens; ++n) {
        if (state->cancelled.load()) break;
        const llama_token id = llama_sampler_sample(state->sampler, state->ctx, -1);

        if (llama_vocab_is_eog(state->vocab, id)) {
            LOGI("EOG token %d, stop", id);
            break;
        }

        llama_sampler_accept(state->sampler, id);

        batch_clear(state->batch);
        batch_add(state->batch, id, n_past++, /*logits*/ true);
        if (llama_decode(state->ctx, state->batch) != 0) {
            LOGE("llama_decode failed on generated token");
            break;
        }

        const int32_t len = llama_token_to_piece(
            state->vocab, id, piece_buf, sizeof(piece_buf), /*lstrip*/ 0, /*special*/ false);
        if (len <= 0) continue;

        cached += std::string(piece_buf, len);
        if (is_valid_utf8(cached.c_str())) {
            full_text += cached;
            invoke_callback(env, callback, onToken, cached);
            if (env->ExceptionCheck()) return nullptr;
            cached.clear();
        }
    }
    // 尾字节没凑齐也交付（避免丢失最后一个多字节字符）
    if (!cached.empty()) {
        full_text += cached;
        invoke_callback(env, callback, onToken, cached);
        if (env->ExceptionCheck()) return nullptr;
    }

    llama_sampler_reset(state->sampler);
    return env->NewStringUTF(full_text.c_str());
}

JNIEXPORT void JNICALL
Java_dev_ondevice_gemma_llm_NativeLlama_llamaCancel(JNIEnv*, jobject, jlong handle) {
    auto* state = reinterpret_cast<LlamaState*>(handle);
    if (state) state->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_dev_ondevice_gemma_llm_NativeLlama_llamaRelease(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* state = reinterpret_cast<LlamaState*>(handle);
    if (!state) return;
    if (state->sampler) llama_sampler_free(state->sampler);
    llama_batch_free(state->batch);
    if (state->ctx) llama_free(state->ctx);
    if (state->model) llama_model_free(state->model);
    llama_backend_free();
    delete state;
}

} // extern "C"
