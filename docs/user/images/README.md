# Screenshots of the user guides

Made by `browser-test/tests/docs-screenshots.spec.js` (opt-in: `DOCS_SCREENSHOTS=1`, Chromium
only), from the browser tests' fixture: the dark theme (the tool's default), English, a 1280x800
viewport at device scale factor 2, placeholder images and generated accounts. Each file is named
after the guide and section that uses it; the guides show them at their CSS size (`width`).

Every image is a Playwright page or element screenshot: no browser or operating-system window.
The fixture's files carry real Commons file names and authors, so the spec replaces them with
numbered examples before each shot, and the link to share shows a neutral host.

**Platform of the current set:** Windows 11 Pro (10.0.26200), Playwright 1.64.1 with its bundled
Chromium (156), the app's own font stack, 2026-11-05. Text rendering depends on the operating
system, so regenerate all the images together on one platform and update this line.

**Size:** 23 images, about 3.2 MB in all; the largest (four of them, the galleries' and large
views' shots) is about 404 KB, the rest are under 400 KB. Photos make 2x PNGs large, so
`browser-test/docs-optimize-png.py` reduces each image to a 256-colour palette after the spec; the
shots are cropped to the relevant top of the page, and the device scale stays 2.

## Photos

The shots show real photos instead of grey boxes: public-domain and CC0 files from Wikimedia
Commons (36 photos, `browser-test/docs-photos.json`, all CC0 entries of Wiki Loves Monuments and
Wiki Loves Earth in Ukraine; the licence of each is checked again against the Commons API when it
is fetched). Nine more are degraded copies of them (blur, a tilted horizon, overexposure,
underexposure, low resolution with JPEG artefacts), made at setup time, so that the yes/no
examples have clearly bad files. The photos are never committed, only the screenshots.

The fixture's image stub, in its docs mode, serves them: a file name always maps to the same photo
(`gatling.setup.DocsPhotos`), and one file in four gets a degraded one. The file names shown on the
shots are numbered examples ("Example photo N", "Author N"), which the spec writes.

## Regenerating the shots

```bash
pip install pillow                                    # once
python browser-test/docs-photos.py                    # fetch (anonymous) into target/docs-photos/
UI_IMAGES=docs node browser-test/fixture-server.mjs start
cd browser-test && DOCS_SCREENSHOTS=1 npx playwright test docs-screenshots --project=chromium
cd .. && python browser-test/docs-optimize-png.py
node browser-test/fixture-server.mjs stop
```

Without `UI_IMAGES=docs` the server keeps the grey stub (normal browser tests and CI always do).
Regenerate all the shots together, look at each one, and update the platform and size lines.

## Credits

Courtesy credits (not shown on the shots); each file is CC0 on Wikimedia Commons, `File:` name,
theme, author. The photos appear, with the degraded copies of some, in the shots of galleries,
large views and results.

- Bratsky Monastery Laznya Illynska street.jpg (monument), Anntinomy, CC0
- Bratsky Monastery Laznya Illynska street 2019.jpg (monument), Anntinomy, CC0
- Kelii corpus 10 naukma 2019.jpg (monument), Anntinomy, CC0
- Kelii or Korpus 10 naukma 2019.jpg (monument), Anntinomy, CC0
- Starokoxackaya 29-1.jpg (monument), Sempay01, CC0
- Starokozackaya 53.jpg (monument), Sempay01, CC0
- Starokozackaya 66.jpg (monument), Sempay01, CC0
- Troicka8.jpg (monument), Sempay01, CC0
- Voloska 5-14 Kyiv 2019.jpg (monument), Anntinomy, CC0
- Voloska 5-14 Kyiv roof 2019.jpg (monument), Anntinomy, CC0
- Voloska 5-14 roof 2019.jpg (monument), Anntinomy, CC0
- Вірьовочна драбина.jpg (landscape), Віктор Височин, CC0
- Доріжки в лісі поблизу селища Лебедівка.jpg (landscape), Віктор Височин, CC0
- Загальний вигляд братської могили (2015).jpg (landscape), СанчоП, CC0
- Лава на пірсі.jpg (landscape), Віктор Височин, CC0
- Медуза на березі пісчаної коси.jpg (landscape), Віктор Височин, CC0
- Мушлі на піску, Тузловські лімани.jpg (landscape), Віктор Височин, CC0
- На території Шарівського замку, Харківська область - ph02.jpg (monument), Віктор Височин, CC0
- Руїни пірсу.jpg (landscape), Віктор Височин, CC0
- Світанок над косою.jpg (landscape), Віктор Височин, CC0
- Східці на вал (з дубу).jpg (landscape), Nagyrniy Igor, CC0
- Токівський водоспад на р. Кам'янка waterfall on the river Kamyanka.jpg (monument), Novoklimov, CC0
- Тригради.jpg (monument), Жосан1991, CC0
- Чайки на березі моря.jpg (landscape), Віктор Височин, CC0
- "Серетський" гідрологічний заказник.jpg (landscape), Matviyiv.lubomir, CC0
- New Jewish cemetery in Zbarazh. 2015. P 20150503 161145.jpg (landscape), Arxivist, CC0
- New Jewish cemetery in Zbarazh. 2015. P 20150503 161851.jpg (landscape), Arxivist, CC0
- UA.AB.Khonkivtsi - WWII memorial - apr 2022.jpg (landscape), Gikü, CC0
- Ботанічний сад імені академіка Олександра Фоміна. Пам'ятник.jpg (monument), Lenka:), CC0
- Ольшанка річка.jpg (landscape), Ph-Andrew996, CC0
- Пейзаж Гайдари.jpg (landscape), Ph-Andrew996, CC0
- Гірська Україна.jpg (landscape), Slaydoggy, CC0
- Дуб Гаркуші у с. Гаї.jpg (landscape), Olga Loboda 0806, CC0
- Заплава на р.Уж, с.Мотійки.jpg (landscape), Alina Turchenko, CC0
- Захід сонця на річці Уж.jpg (landscape), Alina Turchenko, CC0
- Новий Світ – вид зі стежки Галицина.jpg (landscape), Helen Owl, CC0
