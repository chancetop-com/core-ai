#!/usr/bin/env python3
"""Call the Core AI Gateway with the OpenAI Python SDK."""

import argparse
import getpass
import os
import sys

from openai import APIConnectionError, APIError, APIStatusError, APITimeoutError, OpenAI


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv(
        "CORE_AI_BASE_URL", "https://core-ai-server.connexup-uat.net/api/gateway/v1"),
                        help="Server URL ending in /api/gateway/v1; or CORE_AI_BASE_URL")
    parser.add_argument("--model", default=os.getenv("CORE_AI_MODEL", "deepseek-flash"),
                        help="Published Gateway model ID; or CORE_AI_MODEL")
    parser.add_argument("--prompt", default="请只回复：Gateway Python 调用成功。")
    parser.add_argument("--list-models", action="store_true")
    args = parser.parse_args()
    if not args.base_url:
        parser.error("Set CORE_AI_BASE_URL or pass --base-url ending in /api/gateway/v1")
    if not args.list_models and not args.model:
        parser.error("Pass --model or set CORE_AI_MODEL; use --list-models to find IDs")

    api_key = os.getenv("CORE_AI_API_KEY", "").strip()
    if not api_key and sys.stdin.isatty():
        api_key = getpass.getpass("Core AI API Key (hidden): ").strip()
    if not api_key:
        parser.error("Set CORE_AI_API_KEY, or run interactively to enter it securely")

    try:
        # The SDK adds Authorization: Bearer <key> automatically.
        # Keep retries off so a failed demo does not silently repeat paid calls.
        with OpenAI(api_key=api_key, base_url=args.base_url.rstrip("/"),
                    timeout=90.0, max_retries=0) as client:
            if args.list_models:
                models = client.models.list()
                print(f"Available models: {len(models.data)}")
                for model in models.data:
                    print(model.id)
                return 0

            messages = [{"role": "user", "content": args.prompt}]
            response = client.chat.completions.create(
                model=args.model, messages=messages, stream=False,
            )
            if not response.choices or not response.choices[0].message.content:
                print("No text returned; choose a chat model", file=sys.stderr)
                return 1
            print(response.choices[0].message.content)
            print(f"id: {response.id}")
            print(f"model: {response.model}")
            print(f"finish_reason: {response.choices[0].finish_reason}")
            if response.usage:
                print(f"usage: {response.usage.model_dump_json()}")
        return 0
    except APITimeoutError:
        print("Request timed out after 90 seconds", file=sys.stderr)
    except APIConnectionError:
        print("Connection failed: check the URL, network and TLS certificate", file=sys.stderr)
    except APIStatusError as exc:
        hints = {
            401: "Check the API key and whether it belongs to this environment",
            403: "Access denied",
            404: "Check /api/gateway/v1 and the published model ID",
            429: "Rate or quota limit reached",
        }
        print(f"HTTP {exc.status_code}: {hints.get(exc.status_code, 'Check Gateway server logs')}",
              file=sys.stderr)
    except APIError:
        print("Invalid API response; check Gateway server logs", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
