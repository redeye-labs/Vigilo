# Suoni originali di Vigilo, sintetizzati da zero (nessun campione esterno).
import numpy as np, wave
SR = 44100
def env(n, a=0.005, r=0.05):
    e = np.ones(n); na=int(a*SR); nr=int(r*SR)
    e[:na]=np.linspace(0,1,na); e[-nr:]=np.linspace(1,0,nr); return e
def tone(f, d, harm=(1,0.5,0.25), a=0.005, r=0.05, decay=None):
    t=np.arange(int(d*SR))/SR
    s=sum(h*np.sin(2*np.pi*f*(i+1)*t) for i,h in enumerate(harm))
    s*=env(len(t),a,r)
    if decay: s*=np.exp(-t/decay)
    return s
def sil(d): return np.zeros(int(d*SR))
def save(name, s, peak=0.9):
    s=s/np.max(np.abs(s))*peak
    with wave.open(name,'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((s*32767).astype(np.int16).tobytes())
# Panic: sirena bitonale urgente + sweep, 4 s
t=np.arange(int(4.0*SR))/SR
f=np.where((t*4)%1<0.5, 960.0, 1280.0)            # due toni alternati, 4 cicli al secondo
f=f*(1+0.04*np.sin(2*np.pi*12*t))                 # leggero vibrato
ph=2*np.pi*np.cumsum(f)/SR
panic=np.sin(ph)+0.45*np.sign(np.sin(ph))*0.6+0.3*np.sin(2*ph)
panic*=env(len(t),0.01,0.08)
save('vigilo_panic.wav', panic, 0.95)
# Aware: doppio bip di attenzione, ~1.2 s
b1=tone(1046.5,0.16,(1,0.35,0.12),r=0.03)
b2=tone(1318.5,0.22,(1,0.35,0.12),r=0.06)
aware=np.concatenate([b1,sil(0.09),b2,sil(0.12),b1*0.8,sil(0.09),b2*0.8,sil(0.2)])
save('vigilo_aware.wav', aware, 0.9)
# Info/problemi: tono morbido e discreto, ~0.9 s
info=np.concatenate([tone(659.3,0.5,(1,0.2),a=0.02,r=0.3,decay=0.35), sil(0.05)])
info2=tone(880,0.45,(1,0.15),a=0.02,r=0.3,decay=0.3)
s=np.zeros(max(len(info), int(0.18*SR)+len(info2))); s[:len(info)]+=info; s[int(0.18*SR):int(0.18*SR)+len(info2)]+=0.7*info2
save('vigilo_info.wav', s, 0.6)
