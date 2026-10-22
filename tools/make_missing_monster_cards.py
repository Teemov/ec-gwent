import random
from PIL import Image
C='/home/claude/ec-gwent/src/main/resources/assets/ec_gwent/textures/cards/monsters'
A='/mnt/user-data/attachments/caf171a4-ff5f-56b0-83d7-0681a2999f62'
OUT='/tmp/claude-0/-home-claude/caf171a4-ff5f-56b0-83d7-0681a2999f62/scratchpad/mk'
NAME=(34,26,30,255)
def load(n): return Image.open(f'{C}/{n}.png').convert('RGBA')

# ---------------------------------------------------------------- font from existing banners
font={}
def grab(card, text, y0, spacepx=4):
    im=load(card); px=im.load()
    w=len(text.replace(' ',''))*6-1 + text.count(' ')*(spacepx-1+1) - 0
    # positions: advance 6 per glyph, space advances spacepx
    adv=[6 if ch!=' ' else spacepx for ch in text]
    total=sum(adv)-1
    x=71-(total-1)//2
    # find actual first dark column to align
    first=min(xx for xx in range(9,135) for yy in range(y0,y0+7) if px[xx,yy][:3]==NAME[:3])
    x=first
    for ch,a in zip(text,adv):
        if ch!=' ' and ch not in font:
            font[ch]=[[px[x+i,y0+j][:3]==NAME[:3] for i in range(5)] for j in range(7)]
        x+=a
grab('guardian','GUARDIAN',177); grab('zombie','ZOMBIE',173); grab('creeper','CREEPER',177); grab('the_wither','THE WITHER',173)

def erase_line(im, y0):
    px=im.load()
    for y in range(y0,y0+7):
        for x in range(9,135):
            px[x,y]=px[x,169+(y-y0)%3]
def write(im, text, y0):
    px=im.load()
    adv=[6 if ch!=' ' else 4 for ch in text]; total=sum(adv)-1
    x=71-(total-1)//2
    for ch,a in zip(text,adv):
        if ch!=' ':
            g=font[ch]
            for j in range(7):
                for i in range(5):
                    if g[j][i]: px[x+i,y0+j]=NAME
        x+=a

# ---------------------------------------------------------------- backgrounds (136x161, dithered like the originals)
W,H=136,161
def lerp(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(3))
def dither_grad(top,bot,rnd):
    im=Image.new('RGBA',(W,H)); px=im.load()
    steps=9
    for y in range(H):
        t=y/(H-1)*steps; k=int(t); f=t-k
        c1=lerp(top,bot,k/steps); c2=lerp(top,bot,min(1,(k+1)/steps))
        for x in range(W):
            use2 = f>0.66 or (f>0.33 and (x+y)%2==0)
            px[x,y]=(*(c2 if use2 else c1),255)
    return im
def blocks(im, y0, base, dark, light, rnd, size=8):
    px=im.load()
    for by in range(y0,H,size):
        for bx in range(0,W,size):
            for y in range(by,min(H,by+size)):
                for x in range(bx,min(W,bx+size)):
                    r=rnd.random()
                    c = dark if r<0.18 else light if r>0.85 else base
                    if y==by: c=light if (bx//size+by//size)%2 else base
                    px[x,y]=(*c,255)
def stars(im, n, col, rnd, ymax):
    px=im.load()
    for _ in range(n):
        x=rnd.randrange(30,W); y=rnd.randrange(0,ymax); px[x,y]=(*col,255)

def bg_end(rnd):
    im=dither_grad((16,10,26),(96,66,124),rnd)
    stars(im,45,(200,180,230),rnd,110)
    px=im.load()
    # obsidian pillars in the distance
    for cx,top,w in [(52,40,12),(118,22,14),(92,70,9)]:
        for y in range(top,H):
            for x in range(cx,cx+w):
                px[x,y]=(30,20,42,255) if (x+y)%5 else (48,32,66,255)
    blocks(im,132,(214,218,152),(186,190,124),(232,234,178),rnd)
    return im
def bg_nether(rnd):
    im=dither_grad((36,6,8),(120,34,18),rnd)
    px=im.load()
    for _ in range(40):
        x=rnd.randrange(30,W); y=rnd.randrange(10,125); px[x,y]=(255,170,60,255) if rnd.random()<0.5 else (255,110,30,255)
    # lava pool glow at the back
    for y in range(118,132):
        for x in range(30,W):
            if rnd.random()<0.85: px[x,y]=(230,100,20,255) if (x//3+y)%3 else (255,170,50,255)
    blocks(im,132,(110,36,36),(80,22,24),(140,54,50),rnd)
    return im
def bg_water(rnd):
    im=dither_grad((24,92,120),(10,40,70),rnd)
    px=im.load()
    for sx in (50,84,118):   # light shafts
        for y in range(0,130):
            for x in range(sx+y//6, sx+y//6+6):
                if 0<=x<W and (x+y)%2==0:
                    r,g,b,_=px[x,y]; px[x,y]=(min(255,r+18),min(255,g+24),min(255,b+24),255)
    for _ in range(14):
        x=rnd.randrange(34,W-2); y=rnd.randrange(10,120)
        for dx,dy in ((0,0),(1,0),(0,1),(1,1)): px[x+dx,y+dy]=(170,220,230,255)
    blocks(im,138,(196,180,120),(170,152,96),(214,200,140),rnd)
    # seagrass
    for gx in (40,62,128):
        for y in range(110,138):
            px[gx+(y//6)%2,y]=(60,140,70,255)
    return im

# ---------------------------------------------------------------- mob sprite: downscale, hard edges, dark outline
def sprite(path, target_w=None, target_h=None):
    im=Image.open(path).convert('RGBA')
    im=im.crop(im.getbbox())
    w,h=im.size
    s=min((target_w or 9e9)/w,(target_h or 9e9)/h)
    sm=im.resize((max(1,round(w*s)),max(1,round(h*s))),Image.LANCZOS)
    px=sm.load()
    for y in range(sm.size[1]):
        for x in range(sm.size[0]):
            r,g,b,a=px[x,y]; px[x,y]=(r,g,b,255) if a>110 else (0,0,0,0)
    out=Image.new('RGBA',(sm.size[0]+2,sm.size[1]+2),(0,0,0,0)); out.paste(sm,(1,1))
    op=out.load(); w2,h2=out.size
    edge=[(x,y) for y in range(h2) for x in range(w2) if op[x,y][3]==0 and any(
        0<=x+dx<w2 and 0<=y+dy<h2 and op[x+dx,y+dy][3]>0 for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)))]
    for x,y in edge: op[x,y]=(20,14,24,255)
    return out

def card(base, circle_from, art, mob, mob_pos, name, name_y, outfile):
    im=load(base)
    if circle_from:
        im.paste(load(circle_from).crop((4,4,34,34)),(4,4))
    side=im.crop((5,4,30,164))
    art=art.copy(); art.alpha_composite(mob, mob_pos)
    im.paste(art,(4,4))
    im.paste(side,(5,4))
    erase_line(im,name_y); write(im,name,name_y)
    im.save(outfile)
    im.resize((576,800),Image.NEAREST).save(outfile.replace('.png','_4x.png'))
    return im

rnd=random.Random(7)
end=sprite(f'{A}/fef5a3d3-image.png', target_w=100)
card('guardian',None,bg_end(rnd),end,(30,140-end.size[1]+6),'ENDERMITE',177,f'{OUT}/endermite.png')
cube=sprite(f'{A}/4be8714d-image.png', target_w=74)
card('guardian',None,bg_nether(rnd),cube,(48,134-cube.size[1]+4),'MAGMA CUBE',177,f'{OUT}/magma_cube.png')
dr=sprite(f'{A}/049c281b-image.png', target_h=118)
card('zombie','guardian',bg_water(rnd),dr,(58,142-dr.size[1]+4),'DROWNED',173,f'{OUT}/drowned.png')
sheet=Image.new('RGBA',(3*576+40,800),(30,30,34,255))
for i,n in enumerate(['endermite','magma_cube','drowned']):
    sheet.paste(Image.open(f'{OUT}/{n}_4x.png'),(i*596,0))
sheet.save(f'{OUT}/sheet.png'); print('ok', sorted(font))
