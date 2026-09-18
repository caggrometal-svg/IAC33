# MultimediaStudio integration

## Current repository reality

IAC33's Android UI is currently Jetpack Compose/Kotlin. There is no React/TypeScript application under `src/` and no package.json for an Android web frontend.

Therefore `src/components/multimedia/MultimediaStudio.tsx` is a production-oriented React/Capacitor component reference, but it is **not wired into the current APK build**.

## To make it part of the APK

1. Introduce the React/Capacitor web module under `src/`.
2. Add its package.json, Vite/TypeScript configuration and Capacitor entrypoint.
3. Mount `<MultimediaStudio apiBaseUrl={VITE_AI_API_URL} />` from the MED route.
4. Add a media-processing implementation (WebCodecs/ffmpeg.wasm or native bridge) for real crop/filter/text/audio/export operations.
5. Add dedicated backend/provider endpoints for binary image generation, video generation and TTS. The current backend exposes `POST /v1/ai/generate`, which is text generation.
6. Keep the existing Kotlin editor until the React surface passes Android E2E; then remove the old surface.

## Suggested commit sequence

- `feat(multimedia): add professional React studio shell`
- `feat(multimedia): add timeline and mobile editor controls`
- `feat(multimedia): add AI Studio adapter`
- `feat(multimedia): add media generation endpoints`
- `test(multimedia): add Android E2E for MED and AI Studio`

## CI acceptance

The MED migration is complete only when Android E2E proves:
- editor opens;
- media import works;
- timeline/layers work;
- adjustments and aspect ratios persist;
- speed changes preview;
- text/audio layers can be added;
- export succeeds;
- AI Studio text generation succeeds;
- image/video/TTS generation returns actual media files;
- canceling an AI operation leaves UI responsive.
