#!/usr/bin/env bash
# Campioni REALI (non sintetici) per le prove della v5, presi da progetti open source e scaricati in
# tools/samples-real/ (fuori da git: non sono nostri). Ogni file ha lo sha256 fissato al primo download.
#  - Apache Tika, corpus di prova (Apache-2.0): Outlook .msg, XPS, Pages/Numbers/Keynote, 7z/RAR/tar,
#    FB2, KML/KMZ, PSD, TIFF multipagina, firme .p7m, certificati, DOC/PPT con immagini, DjVu, zstd…
#  - Project Gutenberg (pubblico dominio): "Alice" in MOBI 6 e KF8 (azw3)
#  - kthoom di codedread (MIT): un fumetto .cbz vero
# Uso: bash tools/web-test/fetch-real-samples.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/tools/samples-real"
mkdir -p "$DEST"
FILES=(
"alice.azw3|https://www.gutenberg.org/cache/epub/11/pg11-images-kf8.mobi|b3c9067848d28f8c0f36adbb198b306791884319e8b2f8ea7083f844d8876815"
"alice.mobi|https://www.gutenberg.org/cache/epub/11/pg11-images.mobi|29448cd44f3e6d3db391c6aefc438f53283142d9e8a41ef439718a5d84f36240"
"codedread.cbz|https://raw.githubusercontent.com/codedread/kthoom/master/examples/codedread.cbz|a4086e83566759731ab7258784b1efa9862e4ad05e50dcef9027b6ad3cb4a5e4"
"test-documents.7z|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pkg-module/src/test/resources/test-documents/test-documents.7z|fd6542643633e6b7c6fb3a4eecb8701cd458589649c56bf201f86f190313e96b"
"test-documents.rar|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pkg-module/src/test/resources/test-documents/test-documents.rar|466e4703e8505ccdaaa8fda233c84ce0de7fbdd7e4ec041af45b1e06e5621330"
"test-documents.tar|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pkg-module/src/test/resources/test-documents/test-documents.tar|eb1b4b338227e34dec416de239ec1d271bf740085239118d24b745fed2b48c70"
"test-documents.tgz|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pkg-module/src/test/resources/test-documents/test-documents.tgz|c0adfa472f96a8b99e96a384a00b27cfab0296c9e30b27470020eb9608c180c5"
"test-file-1.csv.bz2|https://raw.githubusercontent.com/apache/tika/main/tika-core/src/test/resources/test-documents/bz2/test-file-1.csv.bz2|3c97d0d9c3dd6e8f95933e3f49f54e1a8b49437de310fa72fbb254356146109c"
"test-outlook2003.msg|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/test-outlook2003.msg|ba3c378c3612a1a72a030f3c198ddede9304ffe466f871585956cb87b553d0b3"
"test.fb2|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-xml-module/src/test/resources/test-documents/test.fb2|72a33d899fc296dff978dc56289aefc9dc09a9f16d0c0661b52b1ffb0548f22a"
"testAI_PS.ai|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testAI_PS.ai|0a9eb7c8b16e3017bc0061be4a43dc1b4235ea8685f7e77be72b18c803387036"
"testBIBTEX.bib|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testBIBTEX.bib|7a6f21edb417b965762bb9b2d041dfd64279eef39d70898e1fc006dbdcfd7ef4"
"testCERT.der|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testCERT.der|7c6909c2177ef201f943a07450e2b454e0471e6c572b19e6257c0b768ae0ba4e"
"testCERT.pem|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testCERT.pem|79cd9b77cb02c6dd852235c4490f6f03ec53125db5728da9290a0611e5ae34d1"
"testDJVU.djvu|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testDJVU.djvu|09921d5e7ac4418c79683a45139d36d49c072afb24dcb8b828c68c66d5c2e1bb"
"testKML.kml|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testKML.kml|123d5c43fd15721da536d1ba03e9fcde8f26b8e42085d63fc48e24396222f9c7"
"testKMZ.kmz|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-zip-commons/src/test/resources/test-documents/testKMZ.kmz|9c187cf902808f7df086702a3ed964de7aa018a5e7c01a3ddf0016186297c8b0"
"testKeynote2013.key|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-apple-module/src/test/resources/test-documents/testKeynote2013.key|8da93f6b566bc9eb22f4791bbfd5f2179162f39bf80968187b164c33d31824db"
"testKeynote2018.key|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-apple-module/src/test/resources/test-documents/testKeynote2018.key|05edd6dfddd7cbbb2c74f8648a68de2e18dd97b36da5822f4b68795010bb935f"
"testMSG.msg|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testMSG.msg|972901d1a0049ed54dc993ba57f6612db4c370acea818601b2133423885070cf"
"testMSG_att_doc.msg|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testMSG_att_doc.msg|4f5ab34d4e6bbb04945319dc0decdcfce785779bda618c89ca2eb0fe82e9caf0"
"testNumbers2013.numbers|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-apple-module/src/test/resources/test-documents/testNumbers2013.numbers|74a834857c02d06dbc399aff8ba475f7708092e42501cc665d7fa489f385831f"
"testPKCS17Sig.xml.p7m|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testPKCS17Sig.xml.p7m|69bb268a09c0373528795ade8773010324e46b69b349aae01aa4c2156dcd3b72"
"testPKCS7_signed_data_def.p7m|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-integration-tests/src/test/resources/test-documents/testPKCS7_signed_data_def.p7m|bfeabc703983e1cda533e88ac03f3f480db7efead5a7d8a799931a014e6f342a"
"testPKCS7_signed_data_def.pem|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-crypto-module/src/test/resources/test-documents/testPKCS7_signed_data_def.pem|d9c87a3996b264aa3f2f3998e15e49a78ac83e2313444739d6acf9398d738c5a"
"testPPT.xps|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testPPT.xps|b2813d3f6494617c2885feb3e9e739d71fe2bc36f98f766a17ec0bcc3ee37b92"
"testPPT_2imgs.ppt|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testPPT_2imgs.ppt|761184c9e17d320c1d43882726825396753b9020cc067ac8b281306825f18710"
"testPPT_various.ppt|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testPPT_various.ppt|54790b849e108dca237b44ebffc53dcc3920e159e84f6d590bff2db906412687"
"testPSD.psd|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-image-module/src/test/resources/test-documents/testPSD.psd|53277dbfc70635b72d918b851b1fccba3715b3217764c781160bd93a7bfe7b6e"
"testPSD2.psd|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-image-module/src/test/resources/test-documents/testPSD2.psd|0541e32bf3bb28755a0bbebb0a756ff85f429ba6ebd8d79bbb5d08c3df49f831"
"testPages.pages|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-apple-module/src/test/resources/test-documents/testPages.pages|a276a2784a9ce6ea4aba7fe2cbfd555fe2edecf4db7dbe1f0998c803a150f446"
"testPages2013.pages|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-apple-module/src/test/resources/test-documents/testPages2013.pages|1819dd1f4a04fd22018afcaa3743802f891e092c0974a4193498fb4a1173f2ee"
"testTIFF_multipage.tif|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-ocr-module/src/test/resources/test-documents/testTIFF_multipage.tif|3e425e4682be75c2a0ebb2f4168f9782df3da1580e7e72b00140b228110fdd90"
"testWORD_1img.doc|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testWORD_1img.doc|6c94d2c13ebed034f3046969761e6f2a2acf361baf73a8f5086f1453a366e88a"
"testWORD_3imgs.doc|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testWORD_3imgs.doc|5ceb1e33c8a5fa1bcd7ebe8202efc8061dd6dd4dc138c1e15091471438ab7254"
"testWORD_bold_character_runs.doc|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testWORD_bold_character_runs.doc|ceef10bcf49319a0ed608144cc66bd429cb601cec227fac8fdaf6329121c91c1"
"testWORD_various.doc|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testWORD_various.doc|87a69bd33b7d6134f802b54d95ae0cadb7a9f4bda0d8bab96daa9abde42be180"
"testXPS_various.xps|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testXPS_various.xps|012724092977a0f3882d9c899ad564c96db6ee684fd03fc997d5183357365fa1"
"testZSTD.zst|https://raw.githubusercontent.com/apache/tika/main/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pkg-module/src/test/resources/test-documents/testZSTD.zst|46f9f62efa91681b9bcb5cdd390bbe540fb565c0ed0ab2c65a86eb575116d3fc"
)
for row in "${FILES[@]}"; do
  IFS='|' read -r NAME URL SUM <<< "$row"
  F="$DEST/$NAME"
  if [ ! -f "$F" ] || [ "$(sha256sum "$F" | cut -d' ' -f1)" != "$SUM" ]; then
    curl -sSfL --retry 3 --retry-delay 3 --retry-max-time 60 -A "Mozilla/5.0 (151 Viewer test)" "$URL" -o "$F.tmp"
    GOT="$(sha256sum "$F.tmp" | cut -d' ' -f1)"
    if [ "$GOT" != "$SUM" ]; then echo "ERRORE: $NAME sha256 $GOT diverso da $SUM" >&2; rm -f "$F.tmp"; exit 1; fi
    mv "$F.tmp" "$F"
  fi
  echo "ok  $NAME"
done
