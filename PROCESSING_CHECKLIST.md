# Cronos processing checklist (from pipeline review)

## P0
- [x] Remove unaligned HdrBlend override after safe merge
- [x] Invalid border mask (no edge-pixel repeat after shift)
- [x] Fewer temp abuses in shift path (mask instead of clamp)

## P1
- [x] Normalized sharpness scoring
- [x] Frame selection after coarse alignment + consistency
- [x] Tile-local motion rejection in merge
- [x] Global abort → sharpest single frame when motion high

## P2
- [x] Edge-aware denoise before color boost
- [x] Restrained saturation / skin protect
- [x] Brighter-neutral WB (mild)
- [x] Local tone + detail-confidence sharpen (restrained)

## P3
- [ ] GPU kernels / RenderScript / Vulkan compute (deferred — next track)
- [ ] Learned / TFLite denoise (deferred)
- [ ] Exposure-bracketed true HDR (needs capture-time EC + aligned HDR later)

## Notes
- Gyro still optional aid; image confidence is final merge decision
- HdrBlend.kt kept in repo but not used in photo path until exposure-aware HDR returns
