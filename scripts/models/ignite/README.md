# IGNITE PD-L1 cell detection model

`pdl1_ignite_yolov5m.onnx` is used by `scripts/pdl1_tps.groovy`. It detects cells on PD-L1 IHC
and classifies them as `0` PD-L1 negative tumor cell, `1` PD-L1 positive tumor cell or `2` non-tumor cell.

## Source and license

The weights come from the **IGNITE data toolkit** (Diagnostic Image Analysis Group, Radboud University
Medical Center, and partners):

- Dataset and weights: https://zenodo.org/records/17735903 (`models.zip` → `models/pdl1/pdl1/pdl1_detection_weights.pt`)
- Paper: https://arxiv.org/abs/2507.16855
- Code: https://github.com/DIAGNijmegen/ignite-data-toolkit

The weights are licensed under **CC BY-NC-SA 4.0** (https://creativecommons.org/licenses/by-nc-sa/4.0/).
This ONNX file is a format conversion of those weights and is distributed under the same license:
**non-commercial use only**, with attribution to the IGNITE authors. The rest of this repository
is not covered by that license.

## Model

- YOLOv5m, 3 classes, trained on NSCLC PD-L1 IHC (clones 22C3, SP263, E1L3N)
- Input: `[1, 3, 512, 512]` RGB scaled to 0–1, at 0.5 µm/px
- Output: `[1, 16128, 8]`, one row per candidate box: `cx, cy, w, h, objectness, 3 class scores`
- Post-processing (as in IGNITE `code/pdl1_detection`): confidence 0.5, class-agnostic NMS with IoU 0.6,
  one detection per cell at the box center

Not a diagnostic tool. Results on tissue other than NSCLC, or on stains other than PD-L1 IHC, are not validated.

## How the ONNX file was made

1. Loaded the checkpoint with `torch.load(..., weights_only=True)`, allowlisting only the YOLOv5 and torch
   classes it references, with the official https://github.com/ultralytics/yolov5 code for the model classes.
2. Fused the model, set `Detect.export = True`, and exported with
   `torch.onnx.export(..., opset_version=12, input_names=["images"], output_names=["output0"])`.
3. Checked on four IGNITE PD-L1 test tiles that PyTorch, ONNX Runtime, OpenCV 4.10 (QuPath 0.6) and
   `pdl1_tps.groovy` give the same cells and classes as IGNITE's published inference.

| File | SHA-256 |
|---|---|
| `pdl1_detection_weights.pt` (source) | `b016cc6a724b27f581af0692b5600187b83d729929be2aaf27e4a100194c7456` |
| `pdl1_ignite_yolov5m.onnx` | `78654c33463885d441d33d201eadc7088f3afacd5660094464f0e3dcf41d9f87` |
