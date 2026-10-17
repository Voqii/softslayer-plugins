# Brightness Aura 1.6.6 — 26.3 patch

Upstream has no 26.3 build (latest 1.6.6+26.2, pinned to `>=26.2 <=26.2`).

## Changes (`patch.py` + fabric.mod.json)
- `fabric.mod.json`: minecraft `>=26.3 <=26.3`, version `1.6.6+26.3patch1`
- `KeyMappingImpl.class`: `InputConstants$Type.KEYSYM` -> `KEYBOARD` (renamed in 26.3)
- `KeyMappingImpl.class`: default key `bipush 66` (GLFW B) -> `bipush 5` (SDL scancode B)

## Verified against 26.3
- javap diff: only those two instructions changed
- Mixins (LightmapRenderStateExtractor.extract gamma = OptionInstance.get ordinal 1;
  Options.<init> gamma PUTFIELD) still match; OptionInstance slider interfaces unchanged
- Dev client: loads, Mixin audit passes, keybind registers as key.keyboard.b

Installed in the "GARBAGE 26.2" profile; original 26.2 jar left as `.disabled`.
Replace with the official build once upstream ships 26.3.
