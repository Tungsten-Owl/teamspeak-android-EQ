# Denoiser models

Two ONNX denoisers can be packaged. Only `lightweight-denoise-48k.onnx` (MIT) is
**committed** with this repository; `DFSMN.onnx` is not, because its upstream
weights have separate redistribution terms — see [Licence](#licence) below.
Without a file the matching settings entry reads
“模型未安装”, that model is never selected, and the engine keeps using
DeepFilterNet3 or RNNoise.

| File | Model | Rate | Shape |
| --- | --- | --- | --- |
| `lightweight-denoise-48k.onnx` | `a2heng/lightweight-denoise-48k` (MIT) | 48 kHz | stateless, 10 ms hops + flat cache |
| `DFSMN.onnx` | Alibaba DFSMN ans `48k_causal`, exported by DakeQQ | 48 kHz | block `[1,1,L]` → `[1,1,L]`, causal |

## Install

`lightweight-denoise-48k.onnx` already ships with this repository, so a fresh
clone needs no download for that model. To restore or replace it, download the
MIT-licensed export. **Either name works** — the loader tries
`lightweight-denoise-48k.onnx` first and then the upstream
`purevox_denoise_202606_ep0014_op17.onnx`, so a file copied in unrenamed is
found too:

```powershell
New-Item -ItemType Directory -Force android-ts3\app\src\main\assets\models | Out-Null
Invoke-WebRequest `
  -Uri "https://raw.githubusercontent.com/a2heng/lightweight-denoise-48k/main/purevox_denoise_202606_ep0014_op17.onnx" `
  -OutFile "android-ts3\app\src\main\assets\models\purevox_denoise_202606_ep0014_op17.onnx"
```

```sh
mkdir -p android-ts3/app/src/main/assets/models
curl -L -o android-ts3/app/src/main/assets/models/purevox_denoise_202606_ep0014_op17.onnx \
  https://raw.githubusercontent.com/a2heng/lightweight-denoise-48k/main/purevox_denoise_202606_ep0014_op17.onnx
```

Without the file the settings entry reads “模型未安装”, the model is never
selected, and the engine keeps using DeepFilterNet3 or RNNoise.

## Contract

The opset-17 export is fully static and self-contained, which is why the Android
side needs neither DSP nor a JNI shim:

| | name | shape | meaning |
| --- | --- | --- | --- |
| in | `mix_hop` | `[1, 480]` | one 10 ms waveform hop at 48 kHz |
| in | `cache_in` | `[1, 15176]` | flat streaming cache, zeros on the first hop |
| out | `enh_hop` | `[1, 480]` | enhanced hop, **lags one hop** |
| out | `cache_out` | `[1, 15176]` | cache for the next hop |

Analysis window, overlap-add and the synthesis envelope are constant buffers
inside the graph, so the transform is part of the model. `PureVoxDenoiser` feeds
two hops per 20 ms frame and holds the one-hop delay line itself. The cache is
only valid for the exact hop sequence that produced it: reordering, dropping or
replaying a hop corrupts the recurrent state until the cache is reset.

## DFSMN

There is no prebuilt ONNX in the upstream repository — only export scripts — so
it has to be produced once on a desktop with PyTorch and `modelscope`:

```sh
git clone https://github.com/DakeQQ/Audio-Denoiser-ONNX
cd Audio-Denoiser-ONNX/DFSMN
python Export_DFSMN.py          # writes DFSMN_Optimized/DFSMN.onnx
cp DFSMN_Optimized/DFSMN.onnx /path/to/android-ts3/app/src/main/assets/models/DFSMN.onnx
```

`DfsmnDenoiser` reads the model's declared input length and sizes its window from
it: a fixed-length export must be a whole number of 20 ms hops (960 samples at
48 kHz), a dynamic export gets four hops (80 ms) of lookback. It then slides that
window, runs the graph on it, and keeps only the **last** hop of the output —
valid because the model is causal, so every sample of that hop saw the whole
window as history. Each output sample is produced exactly once, so no crossfade
is involved.

**Export it short.** If the script's fixed chunk length is left at its default
(often one second), the window becomes one second and the added latency becomes
one second too, which is unusable for a call. Export with a dynamic length, or a
fixed length of 3840–7680 samples (80–160 ms).

Cost grows linearly with the window; the DFSMN reference RTF is ~0.01 on a laptop
CPU, so even four hops leave a large real-time margin on a phone.

## Licence

`a2heng/lightweight-denoise-48k` is MIT licensed; see the repository's `LICENSE`.
This is deliberately **not** the model bundled with PureVox itself: those weights
are excluded from the GPL and may not be used outside PureVox without the
author's written permission.

The DFSMN weights come from ModelScope `iic/speech_dfsmn_ans_psm_48k_causal`;
`DakeQQ/Audio-Denoiser-ONNX` is Apache-2.0, but that covers its code, not the
upstream weights. Check the model card before redistributing a build.
