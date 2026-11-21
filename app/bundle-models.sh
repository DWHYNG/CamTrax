#!/usr/bin/env bash
# Downloads all AI models into the app so CamTrax works offline.
# Runs on GitHub Actions during the APK build (it has internet access).
set -euo pipefail
M=app/src/main/assets/models
TMP=$(mktemp -d)
rm -rf "$M"; mkdir -p "$M/coco" "$M/core" "$M/lang" "$M/ort"

( cd "$TMP" && npm pack -q @tensorflow/tfjs@4.20.0 @tensorflow-models/coco-ssd@2.2.3 tesseract.js@5.1.1 tesseract.js-core@5.1.1 @tesseract.js-data/tha@1.0.0 onnxruntime-web@1.17.3 >/dev/null
  for f in *.tgz; do mkdir -p "${f%.tgz}"; tar xzf "$f" -C "${f%.tgz}"; done )

cp "$TMP/tensorflow-tfjs-4.20.0/package/dist/tf.min.js" "$M/"
cp "$TMP/tensorflow-models-coco-ssd-2.2.3/package/dist/coco-ssd.min.js" "$M/"
cp "$TMP/tesseract.js-5.1.1/package/dist/tesseract.min.js" "$TMP/tesseract.js-5.1.1/package/dist/worker.min.js" "$M/"
cp "$TMP"/tesseract.js-core-5.1.1/package/tesseract-core-simd-lstm.wasm.js "$TMP"/tesseract.js-core-5.1.1/package/tesseract-core-lstm.wasm.js "$M/core/"
cp "$TMP/tesseract.js-data-tha-1.0.0/package/4.0.0_best_int/tha.traineddata.gz" "$M/lang/"

# realtime licence-plate detector (open-image-models, MIT) + ONNX runtime for the WebView
cp "$TMP/onnxruntime-web-1.17.3/package/dist/ort.wasm.min.js" "$TMP/onnxruntime-web-1.17.3/package/dist/ort-wasm-simd.wasm" "$M/ort/"
if [ -f docs/models/plate384.onnx ]; then cp docs/models/plate384.onnx "$M/"
else curl -fsSL --retry 3 https://github.com/ankandrew/open-image-models/releases/download/assets/yolo-v9-t-384-license-plates-end2end.onnx -o "$M/plate384.onnx"; fi

if [ "${SKIP_COCO:-0}" != "1" ]; then
  B=https://storage.googleapis.com/tfjs-models/savedmodel/ssdlite_mobilenet_v2
  curl -fsSL --retry 3 "$B/model.json" -o "$M/coco/model.json"
  for s in $(python3 -c "import json,sys;[print(p) for g in json.load(open(sys.argv[1]))['weightsManifest'] for p in g['paths']]" "$M/coco/model.json"); do
    curl -fsSL --retry 3 "$B/$s" -o "$M/coco/$s"
  done
fi
echo "Bundled models:"; du -ah "$M" | sort -k2
