# Astra Open-Source AI Stack

Astra uses open-source projects as architectural references and optional runtime integrations. Source code is not copied unless its license and integration requirements permit it.

## Core local intelligence

- llama.cpp — on-device GGUF inference and native Android integration.
- PocketLLM — reference architecture for a local OpenAI-compatible API, grammar-constrained tool calls, model discovery, and agent loops.
- OfflineLLM — reference for zero-network Android local inference and hardware acceleration.
- Offline AI Assistant — reference for Kotlin/Compose/JNI model management and multiple GGUF models.
- Vesta — reference for offline-first native Android actions, memory, reminders and local GGUF assistants.

## Voice

- openWakeWord — reference for open wake-word detection and custom wake phrase training.
- Porcupine — optional wake-word integration target; licensing and model terms must be respected before redistribution.
- whisper.cpp / Sherpa-ONNX — targets for fully local multilingual speech recognition.
- Piper / Kokoro / Supertonic — targets for local multilingual speech synthesis where model licensing permits.

## Vision / generation

- ML Kit — bundled OCR currently used by Astra.
- llama.cpp vision-capable GGUF models can be supported through the local model adapter.
- stable-diffusion.cpp can be added as an optional image-generation backend on capable devices.

## Web UI

Astra ships its own bundled HTML/CSS/JavaScript UI. It can run entirely offline from Android assets and can also use cloud/network capabilities when the user explicitly enables cloud mode.

## License policy

Astra does not automatically vendor arbitrary GitHub repositories. Each dependency must be reviewed for its license, Android compatibility, native ABI requirements, model licensing, and redistribution terms. The goal is to use the strongest compatible open-source components without silently creating licensing or security problems.
