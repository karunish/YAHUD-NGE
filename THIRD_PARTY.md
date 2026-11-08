# Third-party material

## Design system: NERV UI (MIT)
Colour tokens, type pairing, panel/log/bar patterns and CRT effects follow https://github.com/TheGreatGildo/nerv-ui. The NERV name and any official logo belong to their owners and are not included; `res/drawable/emblem` is an optional slot for your own mark.

MIT License

Copyright (c) 2026 TheGreatGildo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Fonts (SIL Open Font License 1.1)
Noto Serif Display, JetBrains Mono and Saira Extra Condensed, from https://github.com/google/fonts. Licence texts are in `app/src/main/assets/fonts/licenses/`.

## Inspiration only (no code, fonts, images or audio copied)
- https://github.com/PenguinDukeYDAuto/idrive7-EVAHUD (MIT, car-specific JavaScript for a BMW head unit): the idea of real EVA phrases on the HUD. The standby text 出撃待命 ("sortie standby") and the fault stamp 非常事態 ("state of emergency") are used as plain text.
- https://github.com/PenguinDukeYDAuto/eva_hud_app has no licence and bundles MatissePro fonts and EVA audio, so nothing from it is used.
- Optional: if you own a licensed copy of MatissePro-EB.otf, put it in `app/src/main/assets/fonts/` and the HUD uses it for numerals and titles. Do not publish a build that contains it.
