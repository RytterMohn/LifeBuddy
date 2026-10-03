# Third-party components

LifeBuddy's own code and artwork are released under [MIT](LICENSE). Dependencies keep their original licenses. Model weights and downloaded native dependencies are not included in this repository or in the default APK.

| Component | Use | License |
| --- | --- | --- |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Runtime and data serialization | Apache-2.0 |
| AndroidX, Jetpack Compose and Material 3 | Android UI and lifecycle | Apache-2.0 |
| OkHttp 4.12.0, Okio 3.6.0 | HTTPS and streaming | Apache-2.0 |
| CommonMark Java 0.24.0 and GFM table/strikethrough extensions | Markdown rendering | BSD-2-Clause, copyright Robin Stocker |
| Public Suffix List embedded by OkHttp | Domain suffix data | MPL-2.0 |
| Gradle Wrapper | Build bootstrap | Apache-2.0 |
| llama.cpp (optional, not bundled) | Experimental local inference | MIT; retain upstream notices if you build and distribute it |
| Gemma / GGUF weights (optional, not bundled) | Experimental local inference | Model-specific terms; consult the source repository before downloading or distributing |

License texts shipped in the APK are also available here:

- [Apache License 2.0](app/src/main/assets/licenses/Apache-2.0.txt)
- [CommonMark copyright and BSD license](app/src/main/assets/licenses/CommonMark-BSD-2-Clause.txt)
- [Mozilla Public License 2.0](app/src/main/assets/licenses/MPL-2.0.txt)
- [OkHttp Public Suffix List notice](app/src/main/assets/licenses/OkHttp-publicsuffix-NOTICE.txt)

These copies supplement the notices retained in dependency artifacts. Dependency versions are declared in `gradle/libs.versions.toml` and the module build files; the table summarizes direct runtime families, not every transitive artifact.

Upstream projects: [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [OkHttp](https://github.com/square/okhttp/tree/parent-4.12.0), [Okio](https://github.com/square/okio/tree/parent-3.6.0), [CommonMark](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0), [Public Suffix List](https://publicsuffix.org/), [Gradle](https://github.com/gradle/gradle/tree/v8.9.0), [llama.cpp](https://github.com/ggml-org/llama.cpp).
