# Screenshots of the user guides

Made by `browser-test/tests/docs-screenshots.spec.js` (opt-in: `DOCS_SCREENSHOTS=1`, Chromium
only), from the browser tests' fixture: the dark theme (the tool's default), English, a 1280x800
viewport at device scale factor 2, real public-domain photos and generated accounts. Each file is
named after the guide and section that uses it; the guides show them at their CSS size (`width`).

Every image is a Playwright page or element screenshot: no browser or operating-system window.
The fixture's files carry real Commons file names and authors, so the spec replaces them with
numbered examples before each shot, and the link to share shows a neutral host.

**Platform of the current set:** Windows 11 Pro (10.0.26200), Playwright 1.64.1 with its bundled
Chromium (156), the app's own font stack, 2026-11-05. Text rendering depends on the operating
system, so regenerate all the images together on one platform and update this line.

**Size:** 23 images, about 2.9 MB in all; the largest is `organizer-results-by-rate.png`, about
386 KB, and every image is under 400 KB. Photos make 2x PNGs large, so
`browser-test/docs-optimize-png.py` reduces each image to a 256-colour palette after the spec
(dithered where there are photos, so that skies show no bands), and the shots of galleries and
results show only the top of the page; the device scale stays 2.

## Photos

The shots show real photos instead of grey boxes: public-domain and CC0 files from Wikimedia
Commons, in four lists (`browser-test/docs-photos-<list>.json`), by contest and by country:

| List | Photos |
| --- | --- |
| `wlm-ua` | Wiki Loves Monuments, Ukraine |
| `wlm-intl` | Wiki Loves Monuments, other countries |
| `wle-ua` | Wiki Loves Earth, Ukraine |
| `wle-intl` | Wiki Loves Earth, other countries |

**One contest's photos per screenshot:** a screen never mixes Monuments and Earth photos. The
fixture is a Wiki Loves Monuments Ukraine contest, so the current shots use `wlm-ua,wlm-intl` (the
default; about 110 monuments from Ukraine and other countries, so that tiles rarely repeat). The
Earth lists are only fetched when chosen, for a later scenario (for example the screens of a Wiki
Loves Earth round). `DOCS_PHOTOS` names the lists, comma separated, for the fetch and for the
server.

The licence of each file is checked again against the Commons API when it is fetched. Nine more
photos are degraded copies of `wlm-ua` photos (blur, a tilted view, overexposure, underexposure,
low resolution with JPEG artefacts), made at setup time, so that the yes/no examples have clearly
bad files. The photos are never committed, only the screenshots.

The fixture's image stub, in its docs mode, serves them: a file name always maps to the same photo
for the same choice of lists (`gatling.setup.DocsPhotos`), and one file in four gets a degraded
one. The file names shown on the shots are numbered examples ("Example photo N", "Author N"),
which the spec writes.

## Regenerating the shots

```bash
pip install pillow                                    # once
python browser-test/docs-photos.py                    # fetch (anonymous) into target/docs-photos/
UI_IMAGES=docs node browser-test/fixture-server.mjs start
cd browser-test && DOCS_SCREENSHOTS=1 npx playwright test docs-screenshots --project=chromium
cd .. && python browser-test/docs-optimize-png.py
node browser-test/fixture-server.mjs stop
```

For other lists set `DOCS_PHOTOS` (for example `wle-ua,wle-intl`) for the fetch and the server.
Without `UI_IMAGES=docs` the server keeps the grey stub (normal browser tests and CI always do).
Delete `target/ui-shots/thumbs-docs` when the lists change: the image proxy keeps its thumbnails.
Regenerate all the shots together, look at each one, and update the platform and size lines.

## Credits

Courtesy credits, not shown on the shots: Commons file name, author, licence (CC0 for all of them),
year of the contest and country, for the four lists. `python browser-test/docs-photos-credits.py`
writes this section from the lists.

<!-- credits -->

### Wiki Loves Monuments, Ukraine (`wlm-ua`, 18 photos)

- Bratsky Monastery Laznya Illynska street 2019.jpg: Anntinomy, CC0, 2019, Ukraine
- Bratsky Monastery Laznya Illynska street.jpg: Anntinomy, CC0, 2019, Ukraine
- Kelii corpus 10 naukma 2019.jpg: Anntinomy, CC0, 2019, Ukraine
- Kelii or Korpus 10 naukma 2019.jpg: Anntinomy, CC0, 2019, Ukraine
- New Jewish cemetery in Zbarazh. 2015. P 20150503 161145.jpg: Arxivist, CC0, 2024, Ukraine
- New Jewish cemetery in Zbarazh. 2015. P 20150503 161851.jpg: Arxivist, CC0, 2024, Ukraine
- Starokoxackaya 29-1.jpg: Sempay01, CC0, 2020, Ukraine
- Starokozackaya 53.jpg: Sempay01, CC0, 2020, Ukraine
- Starokozackaya 66.jpg: Sempay01, CC0, 2020, Ukraine
- Troicka8.jpg: Sempay01, CC0, 2020, Ukraine
- UA.AB.Khonkivtsi - WWII memorial - apr 2022.jpg: Gikü, CC0, 2022, Ukraine
- Voloska 5-14 Kyiv 2019.jpg: Anntinomy, CC0, 2019, Ukraine
- Voloska 5-14 Kyiv roof 2019.jpg: Anntinomy, CC0, 2019, Ukraine
- Voloska 5-14 roof 2019.jpg: Anntinomy, CC0, 2019, Ukraine
- Загальний вигляд братської могили (2015).jpg: СанчоП, CC0, 2021, Ukraine
- Кар’єр Уж Радванка об’їзна.jpg: Kossstja, CC0, 2022, Ukraine
- Східці на вал (з дубу).jpg: Nagyrniy Igor, CC0, 2020, Ukraine
- Тригради.jpg: Жосан1991, CC0, 2021, Ukraine

### Wiki Loves Monuments, other countries (`wlm-intl`, 93 photos)

- Burgruine Aggstein im Sonnenuntergang.jpg: Felixpauli, CC0, 2024, Austria
- Hof 8 Universität Wien 2022-08-08 03.jpg: Leonhard Lenz, CC0, 2023, Austria
- Windischbach Aquädukt 2022-08-06 04.jpg: Leonhard Lenz, CC0, 2023, Austria
- Tramwaycablebuilding2 - Flickr - socialgalavant.jpg: Kayla Ferguson, CC0, 2017, Colorado
- Dunster (2020-09-16) 25.jpg: Simon Cobb, CC0, 2020, England
- Exhibition Park bandstand, late afternoon.jpg: Audesome, CC0, 2017, England
- Fleming Memorial Hospital.jpg: Audesome, CC0, 2017, England
- Wall in front of 33 Brandling Park, Newcastle upon Tyne.jpg: Audesome, CC0, 2017, England
- Abbaye du Thoronet, le cloître extérieur. Var.jpg: André ALLIOT, CC0, 2017, France
- Asfeld (Ardennes) Église Saint-Didier intérieur.jpg: Havang(nl), CC0, 2016, France
- Bar-le-Duc (Meuse) église Saint-Étienne, chemin de croix, station 01.jpg: Havang(nl), CC0, 2016, France
- Haironville (Meuse) pont (03).jpg: Havang(nl), CC0, 2016, France
- Temple (Paris Metro).JPG: Benoît Prieur, CC0, 2015, France
- Villa Suzanne - PA15000043 - Aurillac - 2014 (04).jpg: Jean-Frédéric, CC0, 2017, France
- Église Saint-Quentin de Contrisson MH (01).jpg: Havang(nl), CC0, 2016, France
- 20160922Weinbergweg 6 Saarbruecken2.jpg: AnRo0002, CC0, 2016, Germany
- 20170901Welde Braeu Schwetzingen.jpg: AnRo0002, CC0, 2017, Germany
- 20180821Zähringerstraße 23 Schwetzingen1.jpg: AnRo0002, CC0, 2018, Germany
- 20180826Römisches Aquädukt4.jpg: AnRo0002, CC0, 2018, Germany
- 20180826Seepferd-Gruppe Schwetzingen1.jpg: AnRo0002, CC0, 2018, Germany
- 20180902Puttenbassin Schwetzingen1.jpg: AnRo0002, CC0, 2018, Germany
- 20180905Casimirianum Neustadt1.jpg: AnRo0002, CC0, 2018, Germany
- 20180920Großer Weiher.jpg: AnRo0002, CC0, 2018, Germany
- 20180920Orangerieparterre Schwetzingen1.jpg: AnRo0002, CC0, 2018, Germany
- Am Mühlgraben 1, Löbau.jpg: ubahnverleih, CC0, 2016, Germany
- Ammergauer Straße 32 bjs190914-02.jpg: Bjs, CC0, 2019, Germany
- Amtsrichterhaus bjs120926-01.jpg: Bjs, CC0, 2019, Germany
- Außenlager Falkensee des KZ Sachsenhausen 04.jpg: Leonhard Lenz, CC0, 2019, Germany
- Bar, Stadthalle Görlitz 2.jpg: ubahnverleih, CC0, 2015, Germany
- Bautzener Straße 2, Uhyst.jpg: ubahnverleih, CC0, 2015, Germany
- Bielefeld - Sparrenburg - 3.jpg: Ath, CC0, 2018, Germany
- Boker-Heide-Kanal - Thune-Aquädukt - 2.jpg: Ath, CC0, 2018, Germany
- Delbrück - Kirchplatz 10.jpg: Ath, CC0, 2017, Germany
- Geschützgießerei Spandau from Sternbergpromenade 03.jpg: Leonhard Lenz, CC0, 2019, Germany
- Heimatmuseum Großröhrsdorf 150325 IMG 6260.JPG: Anika, CC0, 2015, Germany
- Hochbahn.U12.Gleispark.3.jpg: Membeth, CC0, 2015, Germany
- Iggenhausen - St. Alexander.jpg: Ath, CC0, 2019, Germany
- Kachtenhausen - Haferbachstraße 66.jpg: Ath, CC0, 2018, Germany
- Kastanienallee Park Putbus 2021-09-12 03.jpg: Leonhard Lenz, CC0, 2021, Germany
- KdF-Seebad Rügen 2021-09-13 18.jpg: Leonhard Lenz, CC0, 2021, Germany
- Kermeterstollen tower Urftsee 01.jpg: Leonhard Lenz, CC0, 2019, Germany
- Konsulplatz 1, Görlitz.jpg: ubahnverleih, CC0, 2015, Germany
- Konsulplatz 6, Görlitz.jpg: ubahnverleih, CC0, 2015, Germany
- Langerwehe Alte Kirche 2017 013.jpg: Klaus Graf, CC0, 2017, Germany
- Lemgo - Leopoldstraße 34.jpg: Ath, CC0, 2019, Germany
- Marmorbadbrücke beim Auedamm in Kassel.jpg: XenonX3, CC0, 2015, Germany
- Muemoosachstmartinneu012020c99.jpg: Fentriss, CC0, 2020, Germany
- Mueriwastr10072019c97.jpg: Fentriss, CC0, 2020, Germany
- Nordparterre bjs090909-01.jpg: Bjs, CC0, 2021, Germany
- Obere Dorfstraße 36, Ebersdorf (Löbau) (1).jpg: ubahnverleih, CC0, 2016, Germany
- Paderborn - Dom - Rothodenkmal - 7.jpg: Ath, CC0, 2017, Germany
- Paderborn - Geroldskapelle - 4.jpg: Ath, CC0, 2017, Germany
- Paderborn-Galerie Am Abdinghof - Kreuzgang.jpg: Ath, CC0, 2015, Germany
- Peckelsheim - Zehntscheune - 2.jpg: Ath, CC0, 2020, Germany
- Remote view of the Amöneburg mountain 2020-10-07 09.jpg: Leonhard Lenz, CC0, 2025, Germany
- Rheda - Moosstraße 4.jpg: Ath, CC0, 2018, Germany
- Saxoniastraße 12-14, Dresden.jpg: ubahnverleih, CC0, 2015, Germany
- Schloß Holte - Schloss - Innenhof.jpg: Ath, CC0, 2018, Germany
- Schrotholzscheune Hauptstraße 1, Uhyst.jpg: ubahnverleih, CC0, 2015, Germany
- Seehof 95 Wegkapelle (Hofkirchen).jpg: Gliwi, CC0, 2025, Germany
- Steinhagen - Brennerei Schlichte.jpg: Ath, CC0, 2019, Germany
- Urftstaumauer 13.jpg: Leonhard Lenz, CC0, 2019, Germany
- Urftstaumauer 21.jpg: Leonhard Lenz, CC0, 2019, Germany
- View from Frauenberg at Franziskanerkloster Fulda 2025-08-31 09.jpg: Leonhard Lenz, CC0, 2025, Germany
- Volkspark Jungfernheide 06.jpg: Leonhard Lenz, CC0, 2019, Germany
- Wewelsburg (Ortschaft) - 6-bogige Almebrücke.jpg: Ath, CC0, 2016, Germany
- Wiedenbrück - In der Halle 2 (2).jpg: Ath, CC0, 2016, Germany
- Wiedenbrück - Lange Straße 35 - Hausmarken.jpg: Ath, CC0, 2017, Germany
- Zillestraße 89.2.jpg: Hiddenhauser, CC0, 2016, Germany
- 117 Vasile Lupu St, Orhei, Republic of Moldova - 50374485891.jpg: photobankmd, CC0, 2022, Moldova
- Biserica din Hîrtopul Mare 07.jpg: VictorCiobanu, CC0, 2024, Moldova
- MD.BR.Bulboaca - Biserica „Sf. Nicolae” cu școala parohială - jul 2024 - 28.jpg: Gikü, CC0, 2024, Moldova
- MD.BS.Carabetovca - Monument în memoria consătenilor căzuți în război (1941-1945) - jun 2023 - 13.jpg: Gikü, CC0, 2023, Moldova
- MD.BS.Sadaclia - Monument în memoria consătenilor căzuți în război (1941-1945) - jun 2023 - 02.jpg: Gikü, CC0, 2023, Moldova
- MD.C.C - Casa arhitectului Cupcea - mar 2021 - 04.jpg: Gikü, CC0, 2021, Moldova
- MD.C.C - Complexul de clădiri ale fostului spital evreiesc (150B) - oct 2021 - 04.jpg: Gikü, CC0, 2021, Moldova
- MD.C.C - Fondul construit str. Alexandru Hîjdeu - oct 2021 - 07.jpg: Gikü, CC0, 2021, Moldova
- MD.C.C - Fondul construit str. Octavian Goga - oct 2021 - 07.jpg: Gikü, CC0, 2021, Moldova
- MD.C.C - str. A. Plămădeală, 2 - Vilă urbană - oct 2021 - 05.jpg: Gikü, CC0, 2021, Moldova
- MD.C.C - str. București, 34 - Casă cu pasaj comun - sep 2021 - 04.jpg: Gikü, CC0, 2021, Moldova
- MD.C.C - str. Mitropolit Petru Movilă, 27 - Casă de raport - oct 2021 - 01.jpg: Gikü, CC0, 2021, Moldova
- MD.CS.Căinari - Biserica „Sf. Arhangheli Mihail și Gavriil” - may 2022 - 07.jpg: Gikü, CC0, 2022, Moldova
- MD.SR.Soloneț - Conac cu parc al lui Bjozowsky - apr 2023 - 72.jpg: Gikü, CC0, 2023, Moldova
- Sipoteni La Șanț - cetățuie. Copac într-o râpă formată de alunecările de teren (2).jpg: TotalAnarchy, CC0, 2023, Moldova
- Кинешма - Дом Миндовских 01.jpg: Александр Сигачёв, CC0, 2016, Russia
- Кинешма - Площадь революции 2.jpg: Александр Сигачёв, CC0, 2016, Russia
- Mariahilfkapelle beim Oberganischger 2.JPG: Plentn, CC0, 2015, South Tyrol
- Wohnturm beim Steifler in Glaning.jpg: Mai-Sachme, CC0, 2016, South Tyrol
- Bow of fishing boat Frifararen at Vikarvet Museum.jpg: W.carter, CC0, 2019, Sweden
- Gamla Uppsala August 2025 01.jpg: ArildV , CC0, 2025, Sweden
- Gråbrödraklostret i Ystad 03.jpg: 

This image was produced by me, David Castor (user:dcastor). The pictures I sub, CC0, 2021, Sweden
- Tugboat Harry at home in Lysekil harbor 5.jpg: W.carter, CC0, 2019, Sweden
- Elles megaliths.jpg: Smailtn, CC0, 2025, Tunisia

### Wiki Loves Earth, Ukraine (`wle-ua`, 19 photos)

- "Серетський" гідрологічний заказник.jpg: Matviyiv.lubomir, CC0, 2023, Ukraine
- Ботанічний сад імені академіка Олександра Фоміна. Пам'ятник.jpg: Lenka:), CC0, 2021, Ukraine
- Вірьовочна драбина.jpg: Віктор Височин, CC0, 2020, Ukraine
- Гірська Україна.jpg: Slaydoggy, CC0, 2024, Ukraine
- Доріжки в лісі поблизу селища Лебедівка.jpg: Віктор Височин, CC0, 2020, Ukraine
- Дуб Гаркуші у с. Гаї.jpg: Olga Loboda 0806, CC0, 2024, Ukraine
- Заплава на р.Уж, с.Мотійки.jpg: Alina Turchenko, CC0, 2024, Ukraine
- Захід сонця на річці Уж.jpg: Alina Turchenko, CC0, 2024, Ukraine
- Лава на пірсі.jpg: Віктор Височин, CC0, 2020, Ukraine
- Медуза на березі пісчаної коси.jpg: Віктор Височин, CC0, 2020, Ukraine
- Мушлі на піску, Тузловські лімани.jpg: Віктор Височин, CC0, 2020, Ukraine
- На території Шарівського замку, Харківська область - ph02.jpg: Віктор Височин, CC0, 2020, Ukraine
- Новий Світ – вид зі стежки Галицина.jpg: Helen Owl, CC0, 2024, Ukraine
- Ольшанка річка.jpg: Ph-Andrew996, CC0, 2023, Ukraine
- Пейзаж Гайдари.jpg: Ph-Andrew996, CC0, 2023, Ukraine
- Руїни пірсу.jpg: Віктор Височин, CC0, 2020, Ukraine
- Світанок над косою.jpg: Віктор Височин, CC0, 2020, Ukraine
- Токівський водоспад на р. Кам'янка waterfall on the river Kamyanka.jpg: Novoklimov, CC0, 2020, Ukraine
- Чайки на березі моря.jpg: Віктор Височин, CC0, 2020, Ukraine

### Wiki Loves Earth, other countries (`wle-intl`, 37 photos)

- Beowa National Park, New South Wales 23.jpg: Dhx1, CC0, 2016, Australia
- Dove Lake Circuit Walking Track, Cradle Mountain - Lake St Clair National Park 01.jpg: Dhx1, CC0, 2016, Australia
- Dove Lake Circuit Walking Track, Cradle Mountain - Lake St Clair National Park 11.jpg: Dhx1, CC0, 2016, Australia
- Morning views from the summit of Mount Kosciuszko, Kosciuszko National Park 21.jpg: Dhx1, CC0, 2016, Australia
- Mystery Bay, Eurobodalla National Park, New South Wales, Australia 21.jpg: Dhx1, CC0, 2016, Australia
- Sunrise views from the summit of Mount Kosciuszko, Kosciuszko National Park 62.jpg: Dhx1, CC0, 2016, Australia
- Wog Wog to Corang Peak, Corang Arch and Canowrie Brook, Morton National Park 157.jpg: Dhx1, CC0, 2016, Australia
- Almsee und Umgebung 8.jpg: Dimitry Anikin, CC0, 2021, Austria
- Landscape Hardricourt.jpg: ksebf, CC0, 2019, France
- 20150511Feldherrenhügel1.jpg: 
AnRo0002, CC0, 2015, Germany
- 20150518Tripleurospermum inodorum2.jpg: 
AnRo0002, CC0, 2015, Germany
- 20150518Valeriana officinalis.jpg: 
AnRo0002, CC0, 2015, Germany
- 20150519Salix purpurea1.jpg: 
AnRo0002, CC0, 2015, Germany
- 20150521Veronica chamaedrys2.jpg: 
AnRo0002, CC0, 2015, Germany
- 20150522 Geschützer Baum - Baumbestand am Moselradweg Trier - IMG 4473 by sebaso.jpg: Sebaso, CC0, 2015, Germany
- 20160517Cerastium arvense4.jpg: 
AnRo0002, CC0, 2016, Germany
- 20180428Knautia arvensis1.jpg: 
AnRo0002, CC0, 2018, Germany
- 20180501Lychnis flos-cuculi4.jpg: 
AnRo0002, CC0, 2018, Germany
- 20180502Lychnis flos-cuculi2.jpg: 
AnRo0002, CC0, 2018, Germany
- 20190331Ficaria verna.jpg: 
AnRo0002, CC0, 2018, Germany
- 20190422Polygonatum multiflorum3.jpg: 
AnRo0002, CC0, 2019, Germany
- 20190507Anthriscus sylvestris1.jpg: 
AnRo0002, CC0, 2019, Germany
- Beach at Prora 2021-09-13 37.jpg: Leonhard Lenz, CC0, 2022, Germany
- Blauer See Rübeland 2022-04-06 17.jpg: Leonhard Lenz, CC0, 2023, Germany
- Eckerloch Brocken 2022-04-05 47.jpg: Leonhard Lenz, CC0, 2023, Germany
- Hausweiher Westerwaelder Seenplatte (02).jpg: el_tommo, CC0, 2016, Germany
- Near Brentanopark 2020-03-27 02.jpg: Leonhard Lenz, CC0, 2020, Germany
- Seekopf to Herrenwieser See 2020-03-10 28.jpg: Leonhard Lenz, CC0, 2020, Germany
- View from Altkönig 2020-03-25 08.jpg: Leonhard Lenz, CC0, 2020, Germany
- View from Falkenfelsen 2020-03-14 02.jpg: Leonhard Lenz, CC0, 2020, Germany
- View from Falkenfelsen Hertahütte 2020-03-14 05.jpg: Leonhard Lenz, CC0, 2020, Germany
- Weiße Mauer Taunus 2020-03-25 39.jpg: Leonhard Lenz, CC0, 2020, Germany
- MD.C.C - Garden of the Ethnographic museum in Chișinău - may 2017 - 17.jpg: Gikü, CC0, 2017, Moldova
- MD.OC.Naslavcea - Aflorimentul de nisipuri tortoniene de lângă gara Naslavcea - apr 2017 - 03.jpg: Gikü, CC0, 2017, Moldova
- MD.ST.Lozova - Codru scientific reserve (apiary) - may 2017 - 53.jpg: Gikü, CC0, 2017, Moldova
- Cabeza de la Yegua - WLE Spain 2015.jpg: Raúl Hidalgo, CC0, 2015, Spain
- Katterjåkk river with aurora 2022-09-03 09.jpg: Leonhard Lenz, CC0, 2023, Sweden

<!-- credits -->
