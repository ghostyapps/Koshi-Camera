#!/usr/bin/python3
"""Rebuild Koshi overlays and offline previews. Coordinates are final-image pixels."""
from pathlib import Path
import math, random
from PIL import Image, ImageDraw, ImageFont, ImageOps
try:
    import numpy as np  # optional; renderer intentionally works without it
except ImportError:
    np = None
ROOT = Path(__file__).resolve().parent
S, W, H = 2, 3000, 4000
WHITE, GREY, RED, BLACK = '#F2F2F0', '#8C8C8A', '#E2231A', '#000000'
FONTS = ROOT / '../mockup/assets'
JP = '/System/Library/Fonts/Hiragino Sans GB.ttc'
def font(size, weight='Regular', jp=False):
    return ImageFont.truetype(JP if jp else str(FONTS / ('Barlow-'+weight+'.ttf')), round(size*S), index=0)
def box(coords): return tuple(round(v*S) for v in coords)
def rect(d, coords, fill): d.rectangle(box(coords), fill=fill)
def line(d, coords, fill=GREY, width=1): d.line(box(coords), fill=fill, width=max(1,round(width*S)))
def text(d, xy, label, size=30, fill=WHITE, weight='Regular', tracking=0, jp=False):
    f=font(size,weight,jp)
    x,y=xy
    for ch in label:
        d.text((round(x*S),round(y*S)), ch, font=f, fill=fill, anchor='lt')
        x += d.textlength(ch,font=f)/S + tracking
    return x

def strip():
    im=Image.new('RGBA',(W*S,H*S),BLACK); d=ImageDraw.Draw(im)
    # Narrow continuous rebates with a separate black gate line.
    d.rounded_rectangle(box((250,180,2750,3820)), radius=28*S, fill=(0,0,0,0))
    for i in range(20):
        y=96+i*196
        for x in (49,2853):
            d.rounded_rectangle(box((x,y,x+98,y+132)),radius=19*S,fill=WHITE)
    # Printing sits on the inner rebate, clear of the sprockets.
    for right in (False,True):
        label=Image.new('RGBA',(3640*S,64*S)); ld=ImageDraw.Draw(label)
        text(ld,(14,8),'K O S H I  1 0 0',31)
        text(ld,(820,7),'光子',31,jp=True)
        text(ld,(1520,5),'12',38,weight='Light')
        text(ld,(2350,8),'K O S H I  1 0 0',31)
        text(ld,(3260,5),'12A',38,weight='Light')
        label=label.rotate(90 if not right else -90,expand=True)
        im.alpha_composite(label,(174*S if not right else 2764*S,180*S))
    d=ImageDraw.Draw(im)
    # DX-inspired binary edge printing, not an encoded film specification.
    for i,b in enumerate('101101001110101100101011001'):
        if b=='1': rect(d,(176,2390+i*13,224,2397+i*13),WHITE)
    d.polygon([ (x*S,y*S) for x,y in [(2780,3390),(2807,3405),(2780,3420)]],fill=WHITE)
    rect(d,(187,3470,211,3494),RED)
    return im

def darkroom():
    im=Image.new('RGBA',(W*S,H*S),WHITE); d=ImageDraw.Draw(im)
    rect(d,(95,95,2905,3800),BLACK)
    rng=random.Random(135100)
    # Periodic cosine interpolation makes the filed edge continuous and smooth.
    def edge(length,base,axis,reverse=False):
        values=[rng.uniform(-5,5) for _ in range(math.ceil(length/65)+2)]
        pts=[]
        for t in range(0,length+1,3):
            k=t//65; u=(t%65)/65; u=(1-math.cos(u*math.pi))/2
            v=values[k]*(1-u)+values[k+1]*u
            # Three shallow triangular file bites, distributed over the gate.
            if axis=='top': v-=max(0,1-abs(t-1740)/18)*14
            if axis=='left': v-=max(0,1-abs(t-2380)/23)*17
            if axis=='right': v+=max(0,1-abs(t-760)/16)*12
            pts.append((t+250,base+v) if axis in ('top','bottom') else (base+v,t+255))
        return pts[::-1] if reverse else pts
    pts=edge(2500,255,'top')+edge(3350,2750,'right')+edge(2500,3605,'bottom',True)+edge(3350,250,'left',True)
    d.polygon([(round(x*S),round(y*S)) for x,y in pts],fill=(0,0,0,0))
    text(d,(112,3869),'KOSHI',28,BLACK,'Light',7)
    line(d,(112,3843,2888,3843),GREY,0.7)
    text(d,(2745,3865),'光子',30,GREY,jp=True)
    d.ellipse(box((2865,3876,2877,3888)),fill=RED)
    return im

def gallery():
    im=Image.new('RGBA',(W*S,H*S),BLACK); d=ImageDraw.Draw(im)
    rect(d,(125,125,2875,3610),(0,0,0,0))
    cx,cy,r=222,3805,58
    d.ellipse(box((cx-r,cy-r,cx+r,cy+r)),outline=WHITE,width=2*S)
    d.arc(box((cx-44,cy-44,cx+44,cy+44)),195,250,fill=WHITE,width=5*S)
    text(d,(cx-26,cy-28),'光',52,WHITE,jp=True)
    end=text(d,(324,3771),'KOSHI',66,WHITE,'Medium',14)
    d.ellipse(box((end+14,3798,end+28,3812)),fill=RED)
    line(d,(end+72,3766,end+72,3844),GREY,1)
    text(d,(end+112,3781),'光子',46,GREY,jp=True)
    # Static text must never contradict the photo, so no lens or exposure numbers here.
    label='U N P R O C E S S E D'
    f=font(34,'Regular'); tw=d.textlength(label,font=f)/S
    d.text(((2875-tw)*S,3790*S),label,font=f,fill=GREY,anchor='lt')
    return im

DESCRIPTIONS={
 'Koshi135':'35 mm negatif şeridi; delikli siyah kenarlar, film baskısı ve yuvarlatılmış görüntü kapısı.',
 'Darkroom':'El ile eğelenmiş negatif taşıyıcı; düzensiz siyah iç kenar ve kırık beyaz kâğıt payı.',
 'Gallery':'Galeri paspartusu; geniş alt bant, mercek imzası ve sakin teknik satır.'}
def build():
    for name,fn in [('Koshi135',strip),('Darkroom',darkroom),('Gallery',gallery)]:
        im=fn().resize((W,H),Image.Resampling.LANCZOS)
        path=ROOT/(name+'.png'); im.save(path,optimize=True)
        alpha=im.getchannel('A'); pct=alpha.histogram()[0]/(W*H)*100
        assert im.mode=='RGBA' and im.size==(3000,4000) and pct>=60
        if name=='Koshi135': assert pct>=70
        for orientation in ('portrait','landscape'):
            overlay=im if orientation=='portrait' else im.transpose(Image.Transpose.ROTATE_90)
            size=(900,1200) if orientation=='portrait' else (1200,900)
            overlay=overlay.resize(size,Image.Resampling.LANCZOS)
            photo=Image.open(ROOT/('sample_'+orientation+'.jpg')).convert('RGB')
            photo=ImageOps.fit(photo,size,method=Image.Resampling.LANCZOS)
            photo.paste(overlay,(0,0),overlay)
            photo.save(ROOT/f'preview-{name}-{orientation}.jpg',quality=90,optimize=True)
        print(f'{path.name}: {path.stat().st_size/1024:.1f} KB | tam şeffaf alan: %{pct:.2f} | {DESCRIPTIONS[name]}')
    cards=[]
    for name,description in DESCRIPTIONS.items():
        cards.append(f'''<section><h2>{name}</h2><p>{description}</p><div class="views">
        <figure><div class="portrait photo"><img src="{name}.png" alt="{name} dikey çerçeve"></div><figcaption>Dikey · 3:4</figcaption></figure>
        <figure><div class="landscape photo"><img src="{name}.png" alt="{name} eksi 90 derece döndürülmüş çerçeve"></div><figcaption>Yatay · −90° · 4:3</figcaption></figure>
        <figure><div class="portrait checker"><img src="{name}.png" alt="{name} şeffaflık önizlemesi"></div><figcaption>Çıplak çerçeve · dama zemini</figcaption></figure></div></section>''')
    html='''<!doctype html><html lang="tr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Koshi — Film çerçeveleri</title><style>
    @font-face{font-family:Barlow;src:url('../mockup/assets/Barlow-Regular.ttf')}@font-face{font-family:Barlow;src:url('../mockup/assets/Barlow-Medium.ttf');font-weight:500}
    *{box-sizing:border-box}body{margin:0;background:#111;color:#f2f2f0;font-family:Barlow,sans-serif;padding:48px;max-width:1800px;margin:auto}h1{font-size:22px;font-weight:500;letter-spacing:.25em}h2{font-weight:500;letter-spacing:.1em}p,figcaption{color:#8c8c8a}section{margin-top:56px;border-top:1px solid #333;padding-top:16px}.views{display:grid;grid-template-columns:3fr 4fr 3fr;gap:28px;align-items:center}figure{margin:0}figure>div{position:relative;overflow:hidden}img{display:block;width:100%;height:100%}.portrait{aspect-ratio:3/4}.landscape{aspect-ratio:4/3}.photo{background:center/cover no-repeat url('sample_portrait.jpg')}.landscape.photo{background-image:url('sample_landscape.jpg')}.landscape img{position:absolute;width:75%;height:133.333333%;left:12.5%;top:-16.666667%;transform:rotate(-90deg)}.checker{background:conic-gradient(#494949 25%,#303030 0 50%,#494949 0 75%,#303030 0);background-size:24px 24px}figcaption{font-size:14px;margin-top:12px}@media(max-width:850px){body{padding:24px}.views{grid-template-columns:1fr;max-width:500px;gap:30px}}
    </style><h1>KOSHI / 光子</h1><p>Üç film çerçevesi · fotoğraf, yatay görünüm ve şeffaflık incelemesi</p>'''+''.join(cards)+'</html>'
    (ROOT/'preview.html').write_text(html,encoding='utf-8')
    print('Oluşturulan diğer dosyalar: make_frames.py, preview.html, '+', '.join(f'preview-{n}-{o}.jpg' for n in DESCRIPTIONS for o in ('portrait','landscape')))
if __name__=='__main__': build()
