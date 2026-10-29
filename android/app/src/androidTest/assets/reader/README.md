# Reader QA sample provenance

These are test-only samples from libmobi v0.12, distributed in its upstream test suite under the repository LGPL-3.0-or-later license. They are not shipped as user library books. Original/generated fixtures are created on the emulator by ReaderFixtures.kt.

Upstream: https://github.com/bfabiszewski/libmobi/tree/v0.12/tests/samples

| File | SHA-256 |
| --- | --- |
| sample-cp1252.mobi | `e77aa8f99d65f12bc7b5d71f272a2a4a8f36fe6d5c00e9b95c8a926b145ad088` |
| sample-drm-v1.mobi | `631e7afe719c04a91744c22f3021a2af1cafef541f93612a27d629ab74645494` |
| sample-multimedia.mobi | `a301cce8187f1f1ea9562196c46328ca75585da280d4b742827cebd4698697c9` |
| sample-ncx.mobi | `bb33217f3369d8ca6c5a373ca8e21a151be5494f5de6623aa9e7f42fb5fed743` |
| sample-obfuscated-fonts.mobi | `34fc67043eeeaa6563481d6ea2fea1b5349cb73e459a1f10e3aa09186f56302e` |
| sample-unicode-huffdic.mobi | `560dda58429878a64f73381ffddfcf1a59809e7c669a5222666257df8976a68f` |
| sample-unicode-uncompressed.mobi | `9419834c41bf2e7e9ccb78a870e679d56dc4a0a380c5afae3168a2fb5827412d` |

`sample-obfuscated-fonts.mobi` has a real version-8 MOBI/KF8 header; the test imports it with the `.azw3` filename alias. The uncompressed/huffman samples contain genuine KF7+KF8 hybrid data. The DRM v1 sample is used only to assert refusal; no decryption is performed.

## Additional codec fixtures

- `nsis-manual.chm`: LZX-compressed NSIS manual, zlib/libpng license (`NSIS-LICENSE.txt`), obtained from https://github.com/bwhitn/pylibmspack/blob/6abd5acebe8a13d322e5ed1902a2e2c4a7330dd9/tests/fixtures/sample.chm .
- `generated-jb2.djvu`: original 180 x 270 black/white checkerboard and vertical stripe, generated as PBM and encoded losslessly using Ubuntu DjVuLibre `cjb2 3.5.28` (`cjb2 -lossless -dpi 300 original-qa.pbm generated-jb2.djvu`). It contains a real JB2/Sjbz page. The generator sets black pixels where `(20<x<160 && 35<y<235 && (x/12+y/12)%2==0) || 80<x<100`, with integer divisions.
- SHA-256 `nsis-manual.chm`: `3ab384cdc93ff8fab8eb518f39af50d0cf1d5784a31d05e31a6328a72cf435e7`.
- SHA-256 `generated-jb2.djvu`: `95091680c7b0759c56491793d7ff698249034b0629c91cb0406458edf0b47bf8`.
