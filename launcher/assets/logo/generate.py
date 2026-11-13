"""Erzeugt das Icon des Peregrine Client: ein Wanderfalke im Sturzflug, seitlich, kantiger Low-Poly-Stil
(braucht Pillow).

    python generate.py

icon.png   – Fenster-/Taskleisten-Symbol (schwarzes Abzeichen)
falcon.png – nur der Falke, transparent (fuer die Titelleiste im Launcher)
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

ANG = 40
BLACK=(0,0,0,255)
D0=(28,32,44,255); D1=(40,45,59,255); D2=(54,60,76,255); D3=(72,79,97,255); D4=(96,103,122,255)
L0=(146,152,168,255); L1=(192,197,209,255); L2=(226,229,237,255); WH=(246,247,251,255)
Y0=(226,160,36,255); Y1=(250,198,66,255)

def parts():
    P=[]
    # ferner Fluegel
    P.append((D0,[(72,-12),(46,-17.5),(16,-16),(-6,-12),(8,-10),(46,-13)]))
    P.append((L0,[(-6,-12),(6,-14.2),(14,-15.8),(4,-12.6)]))
    fedge=[(72,-12),(46,-17.5),(16,-16),(-6,-12)]
    P.append((L0,fedge+[(x,y+0.7) for x,y in reversed(fedge)]))
    # Schwanz, gebaendert
    P.append((D2,[(26,-3),(-3,-4.5),(-5,4.5),(26,6.5)]))
    for i,x in enumerate([20,14,8,2]):
        P.append((L1,[(x,-3.6),(x-3,-3.8),(x-3,5.4),(x,5.6)]))
    P.append((WH,[(-3,-4.5),(-5,-4.6),(-7,4.6),(-5,4.5)]))
    # Bauch mit Baenderung
    P.append((L1,[(90,5),(80,12),(62,14),(44,12.5),(28,7.5),(30,0),(84,0)]))
    for x,y in [(74,11.5),(66,12.8),(58,12.9),(50,12.3),(42,10.8),(34,8.8)]:
        P.append((L0,[(x,y),(x-2.6,y+0.1),(x-3.2,y-1.4),(x-0.6,y-1.6)]))
    # Krallen eingezogen
    P.append((Y0,[(63,12.5),(69,13.2),(70,15.2),(65,15.4)]))
    P.append((D0,[(65,15.4),(70,15.2),(69.2,16.6),(66,16.6)]))
    # naher Fluegel
    P.append((D1,[(84,-9),(72,-14),(55,-15.5),(38,-14.5),(22,-11.5),(8,-8.5),(-6,-5),(4,-1),(20,2.5),(40,5.5),(58,6.5),(74,4.5),(84,1)]))
    P.append((D2,[(81,-8),(69,-12.5),(55,-13),(50,-5),(66,-2),(81,-2.5)]))
    P.append((D3,[(76,-6.5),(64,-10),(57,-8),(64,-3.5),(76,-3.5)]))
    P.append((D2,[(52,-13),(38,-12.6),(36,-6),(50,-5)]))
    P.append((D3,[(48,-11),(40,-10.6),(39,-7),(47,-6.6)]))
    # Armschwingen mit hellen Kanten
    P.append((L0,[(74,4.5),(58,6.5),(57,4),(73,2.5)]))
    P.append((L2,[(58,6.5),(40,5.5),(40,3.4),(57,4)]))
    # Handschwingen: lange ueberlappende Federn, helle Unterkante + Spitze
    for k in range(5):
        ry1=-12.5+k*3.6; ry2=ry1+4.6; rx=44-k*2
        tx=-6+k*2.6; ty=-6.2+k*1.9
        base=[D1,D2,D2,D3,D3][k]
        P.append((base,[(rx,ry1),(tx,ty),(rx-2,ry2)]))
        f=0.55
        bx=rx-2+(tx-rx+2)*f; by=ry2+(ty-ry2)*f
        P.append(([L0,L0,L1,L1,L2][k],[(bx,by),(tx,ty),(bx+1.5,by-1.1)]))
    P.append((WH,[(-6,-5),(-7.4,-5.4),(-3,-4.2)]))
    # Lichtkante entlang Ruecken (wie Vorlage)
    edge=[(84,-9),(72,-14),(55,-15.5),(38,-14.5),(22,-11.5),(8,-8.5),(-6,-5)]
    P.append((L1,edge+[(x,y+0.9) for x,y in reversed(edge)]))
    # Kopf
    P.append((D0,[(80,-9),(86,-12),(93,-11.5),(98,-8),(100.5,-4),(99,2),(94,6),(87,7),(80,4)]))
    P.append((D1,[(86,-12),(93,-11.5),(96,-9),(89,-8.5),(83,-9.5)]))
    P.append((L1,[(86,3),(91,1),(96,3),(93,6),(87,7)]))
    P.append((L2,[(92,1.4),(96,3),(94.5,4.6),(92.5,3)]))
    
    # Schnabel
    P.append((Y1,[(98,-6.2),(101,-5.6),(104.5,-2),(104,1),(101.5,0),(99,0.6)]))
    P.append((Y0,[(99,0.6),(101.5,0),(104,1),(102.5,2.3),(100,2.1)]))
    P.append((D0,[(104,1),(104.5,-2),(105.4,0.4),(104.6,2.6)]))
    return P

EYE=(94.2,-5.2)

def render(size, out, badge=True, fill=0.86):
    S=4; n=size*S
    im=Image.new("RGBA",(n,n),(0,0,0,0)); d=ImageDraw.Draw(im)
    if badge: d.rounded_rectangle([0,0,n-1,n-1],radius=int(n*0.22),fill=BLACK)
    a=math.radians(ANG); ca,sa=math.cos(a),math.sin(a)
    rot=lambda p:(p[0]*ca-p[1]*sa, p[0]*sa+p[1]*ca)
    allp=[rot(p) for _,poly in parts() for p in poly]
    xs=[p[0] for p in allp]; ys=[p[1] for p in allp]
    w,h=max(xs)-min(xs),max(ys)-min(ys); sc=n*fill/max(w,h)
    ox=n/2-(min(xs)+w/2)*sc; oy=n/2-(min(ys)+h/2)*sc
    T=lambda p:(ox+rot(p)[0]*sc, oy+rot(p)[1]*sc)
    for col,poly in parts(): d.polygon([T(p) for p in poly],fill=col)
    ex,ey=T(EYE)
    for r,c in [(1.5,Y1),(0.9,BLACK)]:
        d.ellipse([ex-r*sc,ey-r*sc,ex+r*sc,ey+r*sc],fill=c)
    d.ellipse([ex+0.1*sc,ey-0.7*sc,ex+0.5*sc,ey-0.3*sc],fill=WH)
    return im.resize((size,size),Image.LANCZOS)

if __name__ == "__main__":
    here = Path(__file__).parent
    render(256, None).save(here / "icon.png", optimize=True)
    render(512, None, badge=False).save(here / "falcon.png", optimize=True)
