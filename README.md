# NovaProcess — asztrofotó utómunka Androidra

Seestar (és bármilyen) nyers kockák stackelése és feldolgozása telefonon, Siril-szerű asztro
eszközökkel és Lightroom-szerű fotós beállításokkal. Az APK-t a GitHub Actions építi, az app
pedig a GitHub Releases-ből frissíti önmagát (engedély után).

## Mit tud

**Stackelés**
- Bemenet: Seestar `_sub` FITS (16 bit, Bayer → debayer), továbbá JPEG / PNG
- Dark / flat / bias mesterkockák (átlag), kalibráció
- Csillagdetektálás + regisztráció eltolással és forgatással (alt-az mezőforgás kezelése)
- Módszer: átlag, sigma-clip, winsorized sigma-clip; gyenge kockák automatikus elvetése
- 2×2 binning opció (gyors, memóriakímélő)

**Asztro eszközök (lineáris térben)**
- Háttér-gradiens kivonás (sík / másodfokú polinom)
- Háttér semlegesítés, fehéregyensúly csillagokra
- Stretch: Siril-féle autostretch (MTF), asinh, kézi MTF, feketepont
- Csillagcsökkentés, élmegőrző zajszűrés

**Fotó eszközök**
- Expozíció, kontraszt, csúcsfények, árnyékok, fehérek, feketék, gamma, szintek
- Hőmérséklet, árnyalat, telítettség, vibrance, RGB csatorna-egyensúly
- Clarity, dehaze, élesítés
- Forgatás, tükrözés, vágás
- Hisztogram, csípéssel nagyítható előnézet

**Export**: JPEG, PNG, 16 bites TIFF (teljes felbontáson).

## Első beüzemelés (egyszer)

1. Hozz létre egy GitHub repót (pl. `novaprocess`), és told fel ezt a mappát.
2. Írd át a `gradle.properties`-ben a `NOVA_GITHUB_REPO` értékét a saját `tulajdonos/repo` címedre
   (ezt az app a frissítések kereséséhez használja; a Beállítások képernyőn utólag is átírható).
3. **Aláíró kulcs** – enélkül a telefon nem engedi az önfrissítést (minden kiadást ugyanazzal a kulccsal kell aláírni):
   ```bash
   bash scripts/make-keystore.sh
   ```
   A kiírt négy értéket add hozzá a repóhoz: *Settings → Secrets and variables → Actions*:
   `NOVA_KEYSTORE_BASE64`, `NOVA_KEYSTORE_PASSWORD`, `NOVA_KEY_ALIAS`, `NOVA_KEY_PASSWORD`.
4. Első kiadás:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
   A *Release APK* workflow lefut, és a Releases oldalon megjelenik a `NovaProcess-1.0.0.apk`.
5. Töltsd le a telefonra, telepítsd (ismeretlen forrás engedélyezése kell).

## Új verzió kiadása

```bash
git commit -am "..."
git tag v1.0.1
git push && git push origin v1.0.1
```
Az app indításkor (legfeljebb 6 óránként) megnézi a legújabb release-t; ha a tag verziója nagyobb a
telepítettnél, felajánlja a frissítést, engedély után letölti az APK-t és megnyitja a rendszer telepítőjét.
A *Beállítások → Frissítés keresése most* gombbal azonnal is ellenőrizhető.

A `main` ágra tolt minden commitnál a *Build APK* workflow is lefut (debug APK, artifactként letölthető).

## Helyi fordítás

Android Studio (Ladybug vagy újabb) → *Open* → a mappa. Vagy parancssorból Gradle 8.11-gyel: `gradle assembleDebug`.
Min. Android 8.0 (API 26).

## Korlátok, tippek

- A feldolgozás tisztán CPU-n fut Kotlinban; 8 MP-es stacknél az export 20–60 mp lehet.
  Sok kocka esetén a 2×2 binning jelentősen gyorsít.
- A csillagregisztráció eltolást + forgatást illeszt, skálát nem; ugyanazzal az eszközzel készült kockákhoz elég.
- Mesterkockák: átlagolás (nem medián), hogy a memória férjen.
- A nyers Seestar FITS-ekhez a stackelés előtt nincs külön debayer-beállítás: a fejléc `BAYERPAT` kulcsát használja.
