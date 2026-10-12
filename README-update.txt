Langkah pasang (Termux):

cd ~/macro-recorder
rm -rf app/src/main
unzip -o ~/storage/downloads/macro-recorder-update.zip -d .
mkdir -p ci && ls ci

Lalu tambahkan step ini di .github/workflows (sebelum step build):

      - name: Pakai debug keystore tetap
        run: |
          mkdir -p ~/.android
          cp ci/debug.keystore ~/.android/debug.keystore

git add -A
git commit -m "Tahap 2: rekam sentuhan + panel Rec/Stop/Play/Settings"
git push
