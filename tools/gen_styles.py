#!/usr/bin/env python3
"""Génère app/src/main/assets/styles.json.

Notation des filtres (couche "style", la correction FT1 est ajoutée par l'appli) :
  ls55+4      low shelf 55 Hz +4 dB (Q 0.7)
  pk2500+1.5q1.2  peak 2500 Hz +1.5 dB Q 1.2 (Q par défaut 1.0)
  hs10k+1     high shelf 10 kHz +1 dB (Q 0.7)
Rappel : le FT1 a déjà ~+4 dB de grave, d'où des boosts de grave modérés.
"""
import json, re, pathlib

TOK = re.compile(r"^(ls|pk|hs)([\d.]+)(k?)([+-][\d.]+)(?:q([\d.]+))?$")

def parse(spec):
    out = []
    for t in spec.split():
        m = TOK.match(t)
        if not m:
            raise ValueError(f"filtre invalide: {t}")
        kind, f, k, g, q = m.groups()
        f = float(f) * (1000 if k else 1)
        typ = kind.upper()
        out.append({"type": typ, "f": f, "g": float(g),
                    "q": float(q) if q else (0.7 if typ in ("LS", "HS") else 1.0)})
    if len(out) > 5:
        raise ValueError(f"max 5 filtres par style (BTR13 = 10 bandes): {spec}")
    return out

def S(name, bpm, spec, desc, subs=()):
    return {"name": name, "bpm": bpm, "spec": spec, "desc": desc, "subs": list(subs)}

# famille: id, nom, couleurs (fond haut, fond bas, accent), animation, styles
F = []
def fam(fid, name, c1, c2, acc, anim, kind, styles):
    F.append(dict(id=fid, name=name, c1=c1, c2=c2, accent=acc, anim=anim, kind=kind, styles=styles))

fam("dnb", "Drum & Bass / Jungle", "#0B3D2E", "#00140D", "#39FF88", "rings", "music", [
    S("Drum & Bass", 174, "ls55+4 pk2500+1.5q1.2 hs10k+1", "Sub vers 50 Hz, caisse claire qui claque", [
        S("Liquid", 174, "ls60+3 pk1200+1q0.8 pk7500-1q4", "Nappes et voix soyeuses, aigus adoucis"),
        S("Neurofunk", 174, "ls50+3.5 pk500+1q0.9 pk2500+2q1.2 hs10k+1.5", "Growl du reese et snare tranchante"),
        S("Jump-Up", 175, "pk45+5q1.2 pk150-1 pk2500+1q1.2", "Wobbles et sub énormes"),
        S("Rollers", 174, "ls50+4.5 hs9k-1", "Sub roulant, aigus reposants pour les longues sessions"),
        S("Dancefloor", 174, "ls55+3.5 pk3000+1.5q1.2 hs12k+1.5", "Énergie club, drops brillants"),
        S("Halftime / Minimal", 170, "ls45+4.5 pk200-1 hs10k+0.5", "Grave profond et espace"),
        S("Techstep", 172, "ls55+3 pk800+1 pk3000+1.5", "Basses métalliques, mid agressif"),
        S("Darkstep", 175, "ls50+3.5 pk300-1 pk4000+1", "Sombre et serré"),
        S("Crossbreed", 175, "pk55+4 pk2800-1q1.5 hs9k+1", "Kick hardcore sur breaks DnB"),
        S("Atmospheric", 172, "ls60+2.5 pk7500-1q4 hs12k+1.5", "Pads et réverbes aériennes"),
        S("Drumfunk", 174, "ls60+2.5 pk180+1q1.2 pk5000+1.5q1.5", "Breaks découpés très détaillés"),
    ]),
    S("Jungle", 165, "ls60+3 pk180+1q1.2 pk5000+1.5q1.5", "Corps et ghost notes des breaks", [
        S("Ragga Jungle", 165, "ls50+3.5 pk1000+1q0.8 pk5000+1q1.5", "Sub 808 et voix du MC"),
        S("Darkside", 165, "ls55+3.5 pk250-1 pk4500+1", "Basses sombres, breaks secs"),
        S("Atmospheric / Intelligent", 165, "ls60+2.5 hs12k+1.5", "Nappes et air"),
        S("Old School 90s", 160, "ls70+2.5 pk3000+1 hs12k+2", "Redonne de l'éclat aux vinyles"),
    ]),
])

# Mazda CX-5 2e génération (2017-2024), Bose 10 HP : tweeters 25 mm aux montants, 16,5 cm portes avant,
# 13 cm portes arrière, centre 8 cm, "caisson" 13 cm dans la roue de secours.
# La correction "cx5_base" est appliquée à part (comme celle du FT1). Les styles ci-dessous suivent
# la cible voiture de Harman (grave renforcé sous 100 Hz, aigus en légère pente).
fam("cx5", "Mazda CX-5 Bose", "#3A0508", "#0D0102", "#E8333A", "rings", "music", [
    S("Référence voiture", 0, "ls90+3 hs3000-1", "Cible Harman voiture : grave plein, aigus naturels"),
    S("Électro / DnB / Techno", 174, "pk55+3q1 pk120+1q1.2 pk2500+1 hs10k+0.5", "Sub tenu sans faire saturer le petit caisson", [
        S("Free party au volant", 175, "ls60+3.5 pk2500+1.5 hs12k-1", "Façade de sound system, avec Punch Moyen"),
        S("Jungle / breaks", 165, "ls60+2.5 pk180+1q1.5 pk5000+1.5q1.5", "Corps des breaks, ghost notes"),
    ]),
    S("Rap / Trap", 140, "pk50+3q1.1 pk200-1 pk1500+1.5", "808 et voix bien devant"),
    S("Rock / Pop", 120, "pk100+1.5 pk3000+1 hs10k+1", "Batterie et guitares"),
    S("Voix : podcasts, radio, GPS", 0, "ls120-3 pk2500+2.5q0.9", "Paroles nettes même sur la route"),
    S("Autoroute", 0, "ls80+3 pk2000+1.5 hs8k+1", "Compense le bruit de roulement (avec AudioPilot coupé)"),
    S("Ville / nuit calme", 0, "ls70+1 hs8k-1", "Doux à bas volume"),
])

fam("universel", "Universel", "#1A1A2E", "#05050C", "#9AD0FF", "waves", "music", [
    S("Tout-terrain", 0, "ls50+2 pk2500+0.5 hs10k+0.5", "Pour les playlists mélangées : un peu de sub, rien d'exagéré"),
    S("Tout-terrain + basses", 0, "ls50+3.5 pk200-0.5 hs10k+1", "Plus de sub pour tout ce qui est électro et rap"),
    S("Doux longue écoute", 0, "ls60+1.5 hs8k-1.5", "Aigus adoucis pour les longues sessions"),
])

fam("freeparty", "Free Party & Tekno", "#1E1E10", "#050503", "#E4FF1A", "rings", "music", [
    S("Façade sound system", 180, "ls45+4.5 pk120+1q1.2 pk2500+1.5q1 hs12k-1.5", "Mur de sub, médiums projetés, aigus adoucis comme en plein air", [
        S("Devant les caissons", 180, "ls45+5.5 pk2500+1 hs10k-2", "Le sub avant tout"),
        S("Milieu du dancefloor", 180, "ls50+4 pk2500+1.5 hs12k-1", "Équilibre de la piste"),
        S("Au loin, dans le champ", 180, "ls60+3 pk800+1.5q0.8 hs6k-3", "Le son qui porte de loin"),
    ]),
    S("Tribe", 175, "pk55+3.5 pk1500+1 pk3000-1q1.5 hs10k+1", "Kick roulant et percus tribales"),
    S("Hardtek", 180, "pk60+3.5 pk300-1 pk2500+1 hs10k+1", "Kick saturé, rythmiques dures"),
    S("Acid Tekno / Mental", 185, "pk60+3 pk1200+1.5 pk3000-1q1.5", "303 hurlante sans fatigue"),
    S("Raggatek", 175, "ls50+4 pk1000+1q0.8 pk2800-1q1.5", "Sub, voix ragga et kick tekno"),
])

fam("techno", "Techno", "#2A0508", "#050102", "#FF2D3D", "rings", "music", [
    S("Hard Techno", 150, "pk60+3.5 pk100+1.5q1.4 pk2800-1.5q1.5 hs9k+1", "Kick et rumble, moins de fatigue sur les kicks saturés", [
        S("Industrielle", 145, "pk55+3.5 pk120+1q1.2 pk3000-2.5q1.2", "Textures dures, médiums calmés"),
        S("Schranz", 155, "pk80+3q1.2 pk2500-2q1.5 hs10k+1.5", "Kick haut et percus métalliques"),
        S("Hardgroove", 145, "pk60+3 pk5500+1.5q1.5 hs10k+1", "Percussions et groove"),
        S("Hard Rave", 155, "pk60+3 pk1500+1 hs10k+1.5", "Leads rave et kick"),
        S("Acid", 145, "pk60+3 pk1200+1.5 hs8k+1", "La 303 qui hurle"),
    ]),
    S("Techno", 130, "pk50+3 hs10k+1", "Kick serré, hats nets", [
        S("Peak Time", 132, "pk50+3.5 pk3000+1 hs10k+1", "Énergie de fin de nuit"),
        S("Minimal", 125, "pk45+2.5 pk7000+1q1.5", "Clics et micro-détails"),
        S("Dub Techno", 122, "ls60+3 pk300+1 hs6k-1.5", "Chaleur et échos feutrés"),
        S("Detroit", 128, "pk55+2.5 pk1000+1 hs10k+1", "Synthés et soul machine"),
        S("Melodic Techno", 124, "pk55+3 pk600+1q0.8 hs12k+1.5", "Arpèges et espace"),
        S("Hypnotic / Raw", 135, "ls45+3.5 pk150+1 pk2500-1", "Rumble hypnotique"),
        S("Tech House", 126, "pk55+3 pk3000+1.5 hs10k+1", "Groove et percus"),
    ]),
])

fam("house", "House", "#2B1E05", "#0D0802", "#FFB627", "rings", "music", [
    S("House", 124, "pk60+3 pk1000+1q0.8 hs10k+1", "Kick rond, piano et voix", [
        S("Deep House", 122, "ls100+2.5 pk300-1 hs10k+1.5", "Grave chaud et aigus soyeux"),
        S("Chicago", 122, "pk70+2.5 pk2000+1 hs12k+1.5", "Boîtes à rythmes classiques"),
        S("French Touch", 124, "pk80+2.5 pk1000+1.5q0.7 pk5000+1", "Samples filtrés"),
        S("UK Garage", 132, "ls55+3.5 pk1500+1 hs10k+1.5", "Sub et rythmes swing"),
        S("Afro House", 122, "pk60+3 pk3500+1.5 hs10k+1", "Percussions vivantes"),
        S("Progressive House", 126, "pk55+3 hs12k+2", "Montées amples"),
        S("Electro House", 128, "pk55+3.5 pk2500+1 hs10k+1.5", "Basses saturées"),
        S("Nu-Disco", 118, "pk90+2 pk800+1 hs10k+1.5", "Basse funky, cordes"),
        S("Amapiano", 112, "pk45+5q1 pk2000+1 hs10k+1", "Log drum très grave"),
    ]),
])

fam("trance", "Trance & Psy", "#1A0B3B", "#05020F", "#B66BFF", "rings", "music", [
    S("Trance", 138, "pk55+3 pk400+1q0.8 hs12k+2", "Médiums épais, aigus brillants", [
        S("Uplifting", 138, "pk55+2.5 pk400+1 hs12k+2.5", "Supersaws et émotions"),
        S("Vocal Trance", 138, "pk55+2.5 pk2000+1.5q0.9 hs12k+2", "Voix au premier plan"),
        S("Hard Trance", 150, "pk60+3.5 pk2500+1 hs10k+1.5", "Kick plus dur"),
    ]),
    S("Psytrance", 145, "pk50+3.5 pk150-1 pk3000+1.5 hs10k+1", "Kick/basse roulante séparés", [
        S("Goa", 142, "pk55+3 pk1500+1.5 hs10k+1.5", "Leads acides"),
        S("Progressive Psy", 136, "pk50+3 hs10k+1.5", "Groove profond"),
        S("Darkpsy / Forest", 150, "pk50+3 pk2500+1 hs6k+1", "Textures organiques sombres"),
    ]),
])

fam("hard", "Hardcore & Hardstyle", "#3B0A00", "#0F0300", "#FF6A00", "rings", "music", [
    S("Hardstyle", 150, "pk70+3.5 pk400-1 pk3000+1 hs10k+1", "Kick distordu et leads", [
        S("Euphoric", 150, "pk70+3 pk2500+1 hs12k+2", "Mélodies épiques"),
        S("Rawstyle", 152, "pk65+3.5 pk300-1.5 pk2000+1.5", "Kicks bruts"),
    ]),
    S("Hardcore", 180, "pk60+3 pk1500+1 pk3000-1.5q1.5", "Kick massif, médiums apaisés", [
        S("Gabber", 190, "pk55+3 pk2500-2 hs10k+1", "Kick gabber"),
        S("Frenchcore", 200, "pk60+3 pk3000-2 hs10k+1", "Rapide et percutant"),
        S("Uptempo", 210, "pk60+3 pk2800-2.5", "Saturation extrême maîtrisée"),
        S("Happy Hardcore", 175, "pk60+2.5 pk2000+1.5 hs12k+2", "Piano et voix pitchées"),
        S("Speedcore", 250, "pk60+2 pk3000-3 hs8k-1", "Limite la fatigue"),
    ]),
])

fam("bass", "Bass Music & Dubstep", "#04203A", "#01070F", "#00C2FF", "rings", "music", [
    S("Dubstep", 140, "ls45+5 pk200-1 pk2500+1", "Sub énorme, wobbles lisibles", [
        S("UK / Deep Dubstep", 140, "ls40+5 hs8k-1", "Sub ressenti, minimalisme"),
        S("Riddim / Brostep", 150, "pk50+4.5 pk800+1 pk3000+1.5", "Basses mécaniques"),
        S("Tearout", 150, "pk55+4 pk2500+1", "Agressif"),
        S("Melodic Dubstep", 150, "ls50+3.5 hs12k+2", "Mélodies et drops"),
    ]),
    S("Trap EDM", 140, "ls45+5 hs10k+1.5", "808 et hats"),
    S("Future Bass", 150, "ls55+3 pk2000+1 hs12k+2", "Accords larges et brillants"),
    S("Grime", 140, "ls50+4 pk1500+1.5q0.9", "Sub et flow du MC"),
    S("Bass House", 126, "pk55+3.5 pk500+1 pk3000+1", "Basses mordantes"),
    S("Footwork / Juke", 160, "ls45+4 pk2500+1.5", "808 et samples hachés"),
    S("Wave", 130, "ls45+3.5 hs10k+1.5", "Sub et pads éthérés"),
])

fam("breaks", "Breakbeat & Rave", "#33001F", "#0D0008", "#FF2F92", "rings", "music", [
    S("Breakbeat", 130, "pk80+2.5 pk200+1q1.2 pk5000+1.5", "Breaks pleins et détaillés"),
    S("Big Beat", 125, "pk80+3 pk1000+1 hs10k+1.5", "Gros breaks et guitares"),
    S("Nu Skool Breaks", 135, "pk60+3 pk3000+1.5", "Basses et snare"),
    S("Breakcore", 180, "pk60+2.5 pk3000-1.5q1.5 pk5000+1", "Chaos lisible"),
    S("Rave 90s", 145, "pk70+2.5 pk1500+1 hs12k+2", "Stabs et pianos rave"),
    S("Electro", 125, "ls55+3.5 pk3000+1 hs10k+1", "808 et synthés robotiques"),
])

fam("chill", "Ambient & Downtempo", "#0A2A33", "#020A0D", "#6EE7F0", "waves", "music", [
    S("Ambient", 70, "ls50+1.5 pk3000-1 hs12k+2", "Espace et douceur"),
    S("Trip-Hop", 90, "ls60+3 pk300+1q0.8 hs8k-1", "Grave enveloppant, grain"),
    S("Lo-fi Hip-Hop", 85, "ls80+2 hs8k-2", "Chaud et feutré"),
    S("Chillout / Lounge", 100, "ls80+1.5 pk1000+1q0.7 hs12k+1.5", "Rond et aéré"),
    S("IDM / Electronica", 120, "pk60+2 pk5000+1 hs12k+2", "Micro-détails"),
    S("Dark Ambient / Drone", 60, "ls40+3 hs6k-1", "Grondements profonds"),
])

fam("synth", "Synthwave & Électro-pop", "#2A0636", "#0A0212", "#FF4FD8", "rings", "music", [
    S("Synthwave", 100, "pk70+2.5 pk2500+1 hs10k+2", "Snare à réverbe et synthés 80s"),
    S("Darksynth", 110, "pk55+3.5 pk300-1 pk3000+1.5", "Basses saturées"),
    S("Synthpop", 118, "pk80+2 pk2000+1.5 hs12k+1.5", "Voix et synthés clairs"),
    S("EBM / Industrial", 125, "pk70+3 pk1200+1.5 pk3500-1", "Basse séquencée martiale"),
    S("Cold / Darkwave", 120, "pk100+1.5 pk1500+1 hs8k+1", "Froid et précis"),
    S("Vaporwave", 80, "ls100+2 hs8k-2", "Nostalgie ouatée"),
    S("Hyperpop", 160, "pk50+3 pk3000+1 pk7500-1q4 hs12k+2", "Saturé mais sans agresser"),
])

fam("hiphop", "Hip-Hop & Rap", "#2E1A00", "#0D0700", "#FFC94D", "rings", "music", [
    S("Rap FR", 90, "pk60+3 pk200-1.5 pk1500+1.5q0.9", "Kick et textes bien compréhensibles"),
    S("Boom Bap", 90, "pk80+3 pk200-1 pk1500+1.5 hs10k+0.5", "Kick/snare sec et samples"),
    S("Trap", 140, "ls45+5 pk200-1.5 pk1500+1 hs10k+1", "808 profonde, voix claire"),
    S("Drill", 142, "pk45+5q1.1 pk1500+1.5 hs9k+1", "Slides de 808"),
    S("G-Funk", 92, "pk70+3 pk1000+1 hs8k+1", "Lead synthé et basse"),
    S("Cloud / Plugg", 140, "ls50+3.5 hs12k+2", "Aérien"),
    S("Phonk", 130, "pk50+4 pk2500+1.5 hs8k+1", "Cowbell et 808 saturée"),
    S("Old School / Jazz Rap", 92, "ls80+2 pk1500+1 hs8k-0.5", "Chaleur vinyle"),
])

fam("rnb", "R&B, Soul & Funk", "#2E0F1A", "#0D0408", "#FF7AA8", "waves", "music", [
    S("R&B", 95, "ls60+3 pk1500+1.5q0.8 hs12k+1.5", "Grave soyeux et voix"),
    S("Neo Soul", 85, "ls80+2 pk400+1 pk1500+1 hs8k-0.5", "Rondeur organique"),
    S("Soul / Motown", 105, "pk120+1.5 pk1500+1.5 hs10k+1", "Réveille les vieux enregistrements"),
    S("Funk", 108, "pk80+2.5 pk700+1 pk3500+1.5", "Slap et guitare rythmique"),
    S("Disco", 120, "pk90+2.5 pk3000+1 hs10k+1.5", "Basse et cordes"),
    S("Gospel", 90, "pk150+1 pk2000+1.5 hs10k+1", "Chœurs"),
])

fam("reggae", "Reggae, Dub & Dancehall", "#0A2A0A", "#030D03", "#FFD400", "waves", "music", [
    S("Roots Reggae", 75, "ls70+3 pk1500+1 hs8k-0.5", "Basse ronde"),
    S("Dub", 70, "ls55+4 pk300-1 hs8k+1", "Sub et échos"),
    S("Steppers / Dub UK", 140, "ls50+4.5 pk2500+1", "Sub sound system"),
    S("Dancehall", 100, "pk55+3.5 pk1500+1.5 hs10k+1", "Riddims et voix"),
    S("Ska / Rocksteady", 140, "pk100+1.5 pk2000+1.5 hs8k+1", "Cuivres et skank"),
])

fam("rock", "Rock", "#2B0F05", "#0D0402", "#FF8A3D", "bars", "music", [
    S("Rock", 120, "pk80+2 pk250-1 pk3000+1.5 hs10k+1", "Batterie et guitares"),
    S("Classic Rock", 115, "pk100+1.5 pk1000+1 hs10k+1.5", "Éclat des années 70"),
    S("Hard Rock", 125, "pk90+2.5 pk250-1 pk2500+2", "Riffs mordants"),
    S("Indie / Alternatif", 125, "pk100+1 pk2000+1.5 hs10k+1.5", "Clair et vif"),
    S("Grunge", 115, "pk100+2 pk400+1q0.8 pk3000+1", "Guitares épaisses"),
    S("Stoner / Desert", 100, "ls80+3 pk400+1.5q0.8 hs8k-1", "Fuzz lourde"),
    S("Post-Rock / Shoegaze", 110, "ls60+1.5 pk2000-1 hs12k+2", "Murs de son"),
    S("Rock Psyché", 110, "pk120+1.5 pk1500+1 hs10k+1.5", "Vintage et coloré"),
])

fam("metal", "Metal", "#1A1A1A", "#050505", "#C0C7D0", "bars", "music", [
    S("Heavy Metal", 130, "pk90+2 pk800+1 pk3000+1.5 hs10k+1", "Riffs et voix puissantes"),
    S("Thrash", 180, "pk80+2.5 pk500-1 pk2500+2 hs10k+1", "Médiums creusés"),
    S("Death Metal", 200, "pk70+3 pk400-1.5q0.8 pk3000+1.5", "Double pédale lisible"),
    S("Black Metal", 200, "pk100+2 pk300+1q0.8 pk6000-1.5", "Donne du corps aux prods fines"),
    S("Doom / Sludge", 70, "ls60+3.5 pk400+1q0.8 hs8k-1.5", "Lourd et lent"),
    S("Metalcore / Djent", 140, "pk60+3 pk500-1.5 pk2500+1.5 hs10k+1", "Chugs serrés"),
    S("Nu Metal", 100, "pk60+3 pk800-1 pk3000+1.5", "Grave et scratchs"),
    S("Metal Symphonique", 140, "pk80+2 pk2000+1 hs12k+1.5", "Orchestre et guitares"),
    S("Metal Indus", 120, "pk65+3 pk1500+1 pk3500-1", "Machines et riffs"),
])

fam("punk", "Punk", "#2E2600", "#0D0B00", "#F2FF3D", "bars", "music", [
    S("Punk", 170, "pk100+1.5 pk2000+1.5 hs8k-1", "Énergie brute, moins de dureté"),
    S("Pop Punk", 170, "pk80+2 pk2500+1.5 hs10k+1", "Refrains clairs"),
    S("Hardcore Punk", 190, "pk90+2 pk3000+1 hs8k-1.5", "Rapide et agressif"),
    S("Post-Punk", 140, "pk120+1.5 pk1000+1 hs8k+1", "Basse en avant"),
])

fam("pop", "Pop", "#3A0A2A", "#0F030B", "#FF5CC8", "waves", "music", [
    S("Pop", 115, "pk60+2.5 pk2000+1.5q0.8 hs12k+2", "Voix et brillance"),
    S("Variété FR", 110, "pk80+1.5 pk1500+1.5 hs10k+1.5", "Voix française au centre"),
    S("K-Pop", 125, "pk55+3 pk2500+1.5 hs12k+2", "Production dense et brillante"),
    S("J-Pop", 130, "pk70+2 pk2500+1.5 hs10k+1.5", "Rapide et lumineux"),
    S("Dance-Pop", 125, "pk55+3.5 pk2000+1 hs12k+2", "Club et radio"),
    S("Indie Pop", 115, "pk100+1.5 pk1500+1 hs12k+1.5", "Léger et clair"),
    S("Ballade", 70, "ls80+1.5 pk1500+1 pk7500-1q4", "Intime et douce"),
])

fam("chanson", "Chanson & Acoustique", "#2A1A0A", "#0D0803", "#E8B37A", "waves", "music", [
    S("Chanson française", 100, "ls100+0.5 pk1800+2q0.8 pk7500-1q4", "La voix et le texte d'abord"),
    S("Folk", 100, "pk150+1 pk3000+1 hs12k+1.5", "Guitares acoustiques"),
    S("Country", 110, "pk120+1 pk2500+1.5 hs10k+1.5", "Twang et voix"),
    S("Acoustique / Unplugged", 90, "pk200+1q0.8 pk3000+1 hs12k+1.5", "Bois des instruments"),
])

fam("jazz", "Jazz & Blues", "#0A1633", "#02060F", "#5AA9FF", "waves", "music", [
    S("Jazz", 120, "ls80-1 pk800+1q0.7 pk3000+1 hs12k+1", "Contrebasse définie, cymbales"),
    S("Swing / Big Band", 160, "pk150+1 pk2500+1.5 hs10k+1", "Cuivres"),
    S("Jazz Manouche", 180, "pk250+1q0.8 pk3000+1.5 hs10k+1", "Pompe et solos"),
    S("Fusion", 120, "pk80+1.5 pk2000+1 hs10k+1.5", "Basse électrique et claviers"),
    S("Smooth Jazz", 95, "ls80+1.5 pk1000+1 hs10k+1", "Doux et rond"),
    S("Nu Jazz", 120, "pk60+2.5 pk1500+1 hs12k+1.5", "Jazz et beats"),
    S("Blues", 90, "pk120+1.5 pk1500+1.5 hs8k-0.5", "Guitare et voix"),
    S("Blues Rock", 115, "pk100+2 pk2000+1.5", "Overdrive chaleureux"),
])

fam("classique", "Classique", "#2A2210", "#0D0A04", "#F2D27A", "particles", "music", [
    S("Symphonique", 80, "ls40+1.5 pk250-0.5 hs12k+1", "Ampleur sans grossir le FT1"),
    S("Musique de chambre", 80, "ls80-1 pk2000+1 hs12k+1", "Détail des cordes"),
    S("Piano solo", 80, "ls60-0.5 pk3500+1 hs12k+0.5", "Attaque et résonance"),
    S("Opéra / Lyrique", 80, "pk1500+1.5q0.8 pk3000+1 pk7500-1.5q4", "Voix sans sifflantes"),
    S("Baroque", 90, "ls80-1 pk3000+1 hs12k+1.5", "Clavecin et cordes"),
    S("Orgue", 60, "ls40+3 hs12k+1", "Les tuyaux graves"),
    S("Musique de film", 90, "ls40+2.5 pk250-0.5 hs12k+1.5", "Orchestre et percussions"),
])

fam("latino", "Latino", "#3A1500", "#0F0500", "#FF9F1C", "rings", "music", [
    S("Reggaeton", 95, "pk55+4 pk1500+1.5 hs10k+1", "Dembow et voix"),
    S("Salsa", 180, "pk100+1 pk2500+1.5 hs10k+1.5", "Cuivres et percus"),
    S("Bachata", 130, "pk100+1 pk2000+1.5 hs10k+1", "Guitares et voix"),
    S("Bossa Nova", 120, "ls80-0.5 pk1500+1 hs10k+1", "Intime"),
    S("Cumbia", 95, "pk80+2 pk2500+1", "Groove et accordéon"),
    S("Baile Funk", 130, "pk50+4.5 pk2000+1 hs8k+1", "808 brésilienne"),
    S("Latin Trap", 140, "ls45+4.5 pk1500+1", "808 et voix"),
])

fam("monde", "Musiques du monde", "#0A2E26", "#020D0B", "#2EE6B8", "particles", "music", [
    S("Afrobeats", 105, "pk60+3 pk2500+1.5 hs10k+1", "Groove et voix"),
    S("Afrobeat", 115, "pk80+2 pk800+1 pk3000+1.5", "Cuivres et polyrythmies"),
    S("Coupé-décalé / Ndombolo", 130, "pk70+2.5 pk3000+1.5 hs10k+1", "Guitares sebene"),
    S("Raï / Chaâbi", 110, "pk100+1.5 pk1500+1.5 hs10k+1", "Voix et derbouka"),
    S("Oriental", 100, "pk200+1q0.8 pk2500+1 hs10k+1", "Oud et percussions"),
    S("Bollywood", 115, "pk60+2.5 pk2000+1.5 hs12k+1.5", "Voix et tablas"),
    S("Zouk / Kompa", 95, "pk70+2.5 pk1500+1 hs10k+1", "Basse et guitares"),
    S("Flamenco", 120, "pk250+1q0.8 pk3500+1.5 hs10k+0.5", "Guitare et palmas"),
    S("Celtique", 110, "pk150+1 pk3000+1 hs12k+1.5", "Violons et flûtes"),
])

fam("video", "Cinéma & Vidéo", "#140C02", "#000000", "#FFB000", "cinema", "video", [
    S("Cinéma", 0, "ls35+2.5 pk2200+1.5", "Explosions et dialogues", [
        S("Action / Blockbuster", 0, "ls35+3.5 pk2200+1", "Impacts et LFE"),
        S("Science-fiction", 0, "ls35+3 hs12k+1.5", "Grondements et effets"),
        S("Horreur / Thriller", 0, "ls40+2.5 pk3000+1 hs10k+1", "Détails qui font sursauter"),
        S("Drame", 0, "ls100-1 pk2200+2", "Dialogues avant tout"),
    ]),
    S("Séries / Dialogues", 0, "ls120-2 pk2200+2.5", "Voix nettes même à faible volume"),
    S("UFC / Sport", 0, "ls100-2 pk1800+2q0.9", "Commentaires et impacts"),
    S("Animés", 0, "ls50+2 pk2000+1.5 hs10k+1", "Voix et musiques épiques"),
    S("Documentaires", 0, "ls100-1.5 pk2000+2", "Narration claire"),
    S("Concerts", 0, "ls60+2 pk3000+1 hs12k+1.5", "Ambiance live"),
    S("Nuit", 0, "ls80+3 hs8k+2", "Faible volume, plus de relief"),
    S("Jeux & émulation", 0, "pk2000+1.5 pk4000+1 hs8k+1", "Pas et effets lisibles", [
        S("Rétro 8/16 bits", 0, "ls80+1 hs6k-2", "Chiptune moins criarde"),
        S("Action / FPS", 0, "ls60-1 pk2000+1.5 pk4000+1.5 hs8k+1", "Positionnement"),
        S("Jeux de rythme", 0, "ls60+2 pk3000+1", "Kick et caisse claire"),
        S("RPG / Ambiance", 0, "ls50+2 hs12k+1.5", "Musiques et ambiances"),
    ]),
])

def slug(s):
    s = s.lower()
    for a, b in (("é","e"),("è","e"),("ê","e"),("à","a"),("â","a"),("ï","i"),("î","i"),("ô","o"),("ç","c"),("&","et")):
        s = s.replace(a, b)
    return re.sub(r"[^a-z0-9]+", "_", s).strip("_")

def build():
    out = {"version": 1, # Correction FT1 v2 : mid-bass épais vers 150-200 Hz (TechPowerUp, Mobile Audiophile, Headfonics),
           # creux 3-4 kHz comblé, pic 5-6 kHz (headphones.com, forum TN) et 7,5 kHz adoucis sans assombrir
           # (Headphoneer trouve déjà les aigus un peu secs). Le sub sous 80 Hz n'est pas touché.
           "ft1_base": parse("pk170-2q1.2 pk3500+1.5q2 pk5500-2q4 pk7500-2q4"),
           # CX-5 Bose : coupe-bas qui protège le caisson 13 cm, appui sur le grave des portes, comblement du creux
           # mesuré vers 180 Hz, bas-médium qui brouille un peu dégagé, aigus légèrement relevés.
           "cx5_base": [{"type": "HP", "f": 32.0, "g": 0.0, "q": 0.7}] + parse("pk100+1.5q0.9 pk180+2q2.5 pk350-2q1.2 hs8k+1.5"),
           "families": []}
    n = 0
    for f in F:
        fam_out = {k: f[k] for k in ("id", "name", "c1", "c2", "accent", "anim", "kind")}
        fam_out["styles"] = []
        for st in f["styles"]:
            sid = f"{f['id']}.{slug(st['name'])}"
            so = {"id": sid, "name": st["name"], "bpm": st["bpm"], "desc": st["desc"],
                  "filters": parse(st["spec"]), "subs": []}
            n += 1
            for sb in st["subs"]:
                so["subs"].append({"id": f"{sid}.{slug(sb['name'])}", "name": sb["name"], "bpm": sb["bpm"],
                                   "desc": sb["desc"], "filters": parse(sb["spec"])})
                n += 1
            fam_out["styles"].append(so)
        out["families"].append(fam_out)
    p = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/styles.json"
    p.write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print(f"{len(F)} familles, {n} réglages -> {p}")

if __name__ == "__main__":
    build()
