# Nghe Kinh Thanh 1925 Offline

## Cai Dat Va Cap Nhat

Ban moi nhat: https://github.com/ZohanDam/BibleAudioVIE1934-Android-Apk/releases/latest

- Tren Android, chi tai tep `.apk` trong Releases va mo de cai dat.
  Khong can tai Source code hoac update.json. Audio tai rieng trong ung dung.
- Cai APK tu Releases mot lan de them chuc nang cap nhat trong ung dung.
- Tu ban 2.19: menu ba cham > bieu tuong cap nhat > Tai cap nhat.
- Tu ban 2.21: nut banh rang > Kiem tra cap nhat (Check for updates).
- Ung dung chi kiem tra khi nguoi dung yeu cau, khong tham do nen.
- Lan dau, Android co the yeu cau bat quyen cai ung dung tu nguon nay.
- Android luon hien man hinh xac nhan cai dat; khong cai am tham.
- Cai de len ban 2.15 tro len, khong go cai dat de giu audio va tuy chon.
- Ban 2.14 cu dung khoa ky khac, khong the cap nhat de bang khoa hien tai.
  Giu ban cu va sao luu audio truoc khi thay doi cach cai dat.
- APK cu o thu muc goc duoc giu de luu tru, khong phai ban moi nhat.

Updater doc GitHub Release moi nhat va asset update.json. Truoc khi mo
trinh cai dat, kiem tra HTTPS, repo, kich thuoc, SHA-256, package, versionCode,
phien ban Android va chu ky trung voi ung dung da cai. Tep nam trong cache
rieng; provider chi chia se APK cap nhat o che do chi doc, khong chia se audio.

Ung dung Android doc va nghe Kinh Thanh Tieng Viet 1923/1925.

## Du lieu

- 3 ban van offline: VIE1925 (193), VIE2010 (151), NIV (111).
- Moi ban co 66 sach, 1.189 chuong; giu cach danh so va gop cau cua YouVersion.
- Ban van VIE1925 lay truc tiep tu YouVersion / Bible.com, version 193,
  cung phien ban voi cac ban thu audio.
- Text duoc dong goi trong `app/src/main/assets/bible.db`.
- Audio khong nam trong APK. Nguoi dung chu dong tai theo chuong, sach,
  hoac toan bo Kinh Thanh sau khi cai dat.
- Audio VIE1925 can khoang 1,16 GB; NIV can khoang 1,08 GB (32 kbps).

Thong tin nguon va giay phep nam trong
`app/src/main/assets/VIE1925_SOURCE.txt`.

VIE2010 va NIV duoc nhap theo xac nhan quyen su dung cua chu ung dung.
Thong tin ban quyen va nguon duoc luu trong bang `text_versions`,
`app/src/main/assets/TEXT_VERSIONS_SOURCE.txt` va muc Thong tin cua ung dung.

## Tinh nang

- Giao dien doc Kinh Thanh theo bo cuc YouVersion.
- Chon Cuu Uoc, Tan Uoc, sach va chuong.
- Sach dang chon co nen noi bat, chu dam va dau tich; khong dung mau do.
- An bang audio bang nut loa van giu hai mui ten chuyen chuong nho.
- Hai mui ten chuyen chuong dung vector day, ro hon o ca hai che do audio.
- Nut thu gon ngay trong bang audio an bang dieu khien, khong dung audio.
- Ban NIV hien "Chapter" o tieu de chuong; ban tieng Viet van hien "Chuong".
- Doc text offline, doi co chu.
- Menu banh rang: Co chu, hai nut VN/EN, hai nut Ngay/Dem, Kiem tra cap nhat, Gioi thieu.
- Ngon ngu giao dien doc lap voi ban dich Kinh Thanh; lua chon duoc ghi nho.
- Ngay/Dem doi ngay trong menu, khong mo hop thoai va khong theo he thong.
- Gioi thieu nam cuoi menu, co tieu de va cac muc noi dung/audio/nguon rieng.
- Ghi nho giao dien; doi giao dien khong khoi dong lai audio.
- Nut ban dich nam duoi tieu de chuong. Doi ban dich giu nguyen sach/chuong,
  khong bat dau audio khi dang tam dung va ghi nho lua chon.
- Bo chon ban dich gon, moi dong mot ma ban dich va dau tich cho lua chon hien tai.
- NIV dung ban thu Max McLean (The Listener's Bible), cung ban NIV 111.
- VIE2010 van dung audio VIE1925 va hien ro ten ban audio.
- Doi ban dich giua NIV/VIE1925 doi ca audio, giu sach/chuong va trang thai
  phat/tam dung. Doi chuong khi tam dung khong tao MediaPlayer hoac stream.
- Dung rieng ten tep, hang doi va vi tri nghe cho NIV; giu tep VIE1925 cu.
- Stream audio khi chua tai.
- Tai audio nen voi 12 luong tai song song.
- Tai mot chuong, mot sach, hoac ca 66 sach.
- Audio da tai duoc giu den khi nguoi dung xoa.
- Phat nen, media notification, tua, toc do va hen gio tat.
- Bang audio thu gon co nut mo rong, ben canh hai mui ten chuyen chuong.
- Notification va lock screen: lui 15 giay, phat/tam dung, chuong sau.
  Android 13+ lay nut tu MediaSession; cac ban cu dung ba notification actions.
- Hen gio co cac moc 15/30/60/90/120/240/480 phut va dem nguoc truc tiep
  trong bang audio va hop hen gio. Dung dong ho monotonic, khong them wake lock.
- Deadline hen gio duoc luu de khong tu phat lai sau khi het gio va service bi tao lai.
  Hen gio cu bi huy khi thiet bi khoi dong lai.
- Tu dong chuyen chuong va ghi nho vi tri nghe.

## Build

Yeu cau Android SDK 35 va JDK 17 tro len.

```powershell
.\gradlew.bat assembleDebug
```

APK mac dinh:

```text
app/build2/outputs/apk/debug/app-debug.apk
```

Ban phat hanh khong bat che do debug, giu dung khoa ky hien tai:

```powershell
.\gradlew.bat testDebugUnitTest lintRelease assembleRelease `
  -PdebugKeystore=C:\Users\damvi\.android\debug.keystore
```

Khong dua keystore, token GitHub, local.properties, cache hoac build output
vao Git. File .gitignore loai tru cac muc nay.

## Phat Hanh Len GitHub

Sau khi test/build thanh cong, commit va push source len main. Dung Git
Credential Manager dang nhap vao tai khoan co quyen push, roi chay:

```powershell
.\tools\publish_release.ps1 `
  -ApkPath .\app\build2\outputs\apk\release\app-release.apk `
  -SourceCommit (git rev-parse HEAD) `
  -MinimumSdk 26 `
  -Notes 'Mo ta thay doi cua ban moi.'
```

Script kiem tra chu ky va build metadata, tao draft release, tai APK va
update.json, kiem tra kich thuoc/checksum GitHub, sau do moi publish.
Khong ghi token vao tep hoac dua khoa ky len GitHub. Moi versionCode phai
lon hon ban truoc; khong thay the APK cua mot release da phat hanh.
Neu upload bi loi, draft duoc giu de kiem tra va khong hien cho updater.

Khi OneDrive khoa thu muc build, chuyen output sang thu muc khac:

```powershell
.\gradlew.bat assembleDebug -PngheBuildDir=C:\Temp\nghekinhthanh-build
```

## Tao lai co so du lieu

Can Python va Beautiful Soup (`pip install beautifulsoup4`).
Importer doc cac trang chuong cong khai cua YouVersion VIE1925:

```powershell
python tools\import_youversion_text.py `
  --manifest app\src\main\assets\bible_manifest.json `
  --database app\src\main\assets\bible.db `
  --cache .youversion-cache
```

Script kiem tra du 66 sach, 1.189 chuong, text cua tung chuong va tinh toan ven
SQLite truoc khi thay database. Cache cho phep tiep tuc sau khi bi ngat.
Audio URL va ten file duoc giu nguyen. `generate_bible_database.py` chi
con dung de tao ban du lieu eBible cu, khong phai nguon text hien tai.

## Nhap Them VIE2010 Va NIV

Chi chay khi co quyen su dung cac ban van nay:

```powershell
python tools\import_additional_versions.py `
  --manifest app\src\main\assets\bible_manifest.json `
  --database app\src\main\assets\bible.db `
  --cache .additional-text-cache
```

Importer dung lai parser YouVersion, kiem tra du 2.378 chuong moi,
giu nguyen bang audio va ban van VIE1925. Phai chay importer nay sau
khi tao lai database VIE1925.

## Audio NIV

Sau khi nhap text, thu thap metadata 1.189 chuong (khong tai audio):

```powershell
python tools\import_niv_audio.py
```

Script doc AudioObject JSON-LD tu trang audio cong khai, kiem tra ban thu
3/version 111, kiem tra 3 stream bang HTTP Range, roi ghi bang audio_chapters.
Cache niv-audio-cache cho phep tiep tuc neu mat ket noi. Giu nguyen bang
chapters, text, ten tep va ma tai cu. Thong tin nguon nam trong
app/src/main/assets/NIV_AUDIO_SOURCE.txt va muc thong tin cua ung dung.

Kiem tra HTTP HEAD cho moi tep va cap nhat tong dung luong:

```powershell
python tools\validate_niv_audio.py
```

Kiem tra playback tren JVM (Robolectric, khong phat audio that):

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```
