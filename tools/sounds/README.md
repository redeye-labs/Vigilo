# Suoni di Vigilo

`app/src/main/res/raw/vigilo_{panic,aware,info}.ogg` sono suoni originali, sintetizzati da zero con
`make_sounds.py` (numpy) e convertiti in Ogg Vorbis con ffmpeg. Nessun campione di terzi: nessuna licenza
esterna, rientrano nella licenza MIT del progetto.

```bash
python3 make_sounds.py   # crea i .wav
for f in panic aware info; do ffmpeg -y -i vigilo_$f.wav -c:a libvorbis -q:a 4 vigilo_$f.ogg; done
```

- `panic`: sirena bitonale urgente (4 s), per l'allarme.
- `aware`: doppio bip di attenzione (1,3 s), per «qualcuno vicino all'auto».
- `info`: tono morbido e discreto (0,6 s), per avvisi e problemi.
