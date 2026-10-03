#!/usr/bin/env python3
"""Opt-in model tests with synthetic fixtures; credentials never appear in argv/reports."""
import argparse
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit


def load_config(args):
    if args.settings or args.credentials:
        if not (args.settings and args.credentials):
            raise ValueError("Both legacy YAML paths are required")
        import yaml  # Optional dependency for explicitly requested legacy imports only.
        settings = yaml.safe_load(args.settings.read_text())
        provider = settings["llm-deepseek"]
        refs = yaml.safe_load(args.credentials.read_text())["refs"]
        base, model, key = provider["baseURL"], settings["agent-default-model"]["model"], refs[provider["apiKeyEnv"]]
    else:
        base = os.environ.get("LIFEBUDDY_API_URL", "")
        model = os.environ.get("LIFEBUDDY_API_MODEL", "")
        key = os.environ.get("LIFEBUDDY_API_KEY", "")
    if not all(isinstance(value, str) and value.strip() for value in (base, model, key)):
        raise ValueError("Missing provider configuration")
    url = urlsplit(base)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("Use an HTTPS base URL without credentials in the URL")
    return base, model, key


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle", default="./gradlew.bat" if os.name == "nt" else "./gradlew")
    parser.add_argument("--settings", type=Path, help="Explicit legacy dsh settings YAML (optional)")
    parser.add_argument("--credentials", type=Path, help="Explicit legacy credentials YAML (optional)")
    parser.add_argument("--suite", choices=["learning", "latency", "apps", "autoskills", "system", "extensions"], default="learning")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--build-jdk", type=int, choices=[17, 21], default=17)
    parser.add_argument("--offline", action="store_true", help="Reuse cached Gradle dependencies; model requests remain online")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        base, model, key = load_config(args)
    except Exception:
        raise SystemExit("Set LIFEBUDDY_API_URL, LIFEBUDDY_API_MODEL and LIFEBUDDY_API_KEY, or provide both YAML paths with PyYAML installed. No credentials were printed.")
    env = os.environ.copy()
    report = args.report or Path(f"build/reports/provider-tests/{args.suite}.json")
    env.update(ANDROIDMODEL_LEARNING_API_TEST="1", ANDROIDMODEL_TEST_API_URL=base,
               ANDROIDMODEL_TEST_MODEL=model, ANDROIDMODEL_TEST_API_KEY=key,
               ANDROIDMODEL_TEST_REPORT=str(root / report))
    print("Running real-provider tests with synthetic fixtures; no phone actions.", flush=True)
    task = {"learning": ":app:learningApiTest", "latency": ":app:latencyApiTest", "apps": ":app:appSkillsApiTest", "autoskills": ":app:autoSkillsApiTest", "system": ":app:systemToolsApiTest", "extensions": ":app:extensionsApiTest"}[args.suite]
    command = [args.gradle, f"-PbuildJdk={args.build_jdk}", task, "--no-daemon"]
    if args.offline:
        command.append("--offline")
    raise SystemExit(subprocess.run(command, cwd=root, env=env).returncode)


if __name__ == "__main__":
    main()
