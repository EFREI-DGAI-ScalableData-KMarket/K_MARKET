"""Visuels SVG des produits, générés à la volée (aucun fichier stocké).

Visuel seul, sans texte : le nom, la catégorie, le prix et le statut sont affichés par l'interface
autour de l'image ; les répéter dans le visuel ferait doublon.
Format 4:3 (400 × 300), celui des cartes du catalogue, pour que rien ne soit rogné.

Couleurs déterministes : un même artiste / une même série / une même marque garde toujours le même dégradé.
Formes génériques par catégorie (pas de pochette ni de logo réels : droits d'auteur).
"""
import hashlib

# Dégradés pastel de luminosité proche (haut-gauche -> bas-droite), accordés au thème rosé de l'interface
PALETTE = [
    ("#f4a6c0", "#e8b8d8"), ("#c9a7e8", "#e6b7d9"), ("#a9c4f0", "#c6b6ec"), ("#f5b99a", "#f2a6b8"),
    ("#9fd6c9", "#b7c8ec"), ("#f3c88f", "#f0a9a0"), ("#b6a3e6", "#f0a8c8"), ("#e9a3b4", "#c7a6dd"),
    ("#a7d3e8", "#d5b3e3"), ("#f2b3c9", "#f6cfa8"), ("#bfb0ea", "#a9d4e4"), ("#e7aecb", "#b9b2ea"),
]

# Icône blanche dessinée autour de (200, 165) ; décalée de -15 px à l'affichage pour être centrée dans 400 × 300
ICONS = {
    "Album": """
      <circle cx="200" cy="165" r="78" fill="#fff" fill-opacity=".9"/>
      <circle cx="200" cy="165" r="56" fill="none" stroke="#000" stroke-opacity=".08" stroke-width="2"/>
      <circle cx="200" cy="165" r="38" fill="none" stroke="#000" stroke-opacity=".08" stroke-width="2"/>
      <circle cx="200" cy="165" r="18" fill="#000" fill-opacity=".15"/>
      <circle cx="200" cy="165" r="6" fill="#fff"/>""",
    "Lightstick": """
      <circle cx="200" cy="120" r="52" fill="#fff" fill-opacity=".35"/>
      <circle cx="200" cy="120" r="36" fill="#fff" fill-opacity=".95"/>
      <rect x="188" y="160" width="24" height="90" rx="10" fill="#fff" fill-opacity=".9"/>
      <rect x="182" y="156" width="36" height="12" rx="5" fill="#fff"/>""",
    "Merch": """
      <path d="M150 100 L180 88 Q200 108 220 88 L250 100 L282 132 L258 154 L244 142 L244 245
               L156 245 L156 142 L142 154 L118 132 Z" fill="#fff" fill-opacity=".9"/>""",
    "Drama": """
      <rect x="120" y="125" width="160" height="110" rx="10" fill="#fff" fill-opacity=".9"/>
      <path d="M120 95 L278 78 L282 108 L124 125 Z" fill="#fff" fill-opacity=".9"/>
      <path d="M150 92 L170 120 M190 87 L210 115 M230 83 L250 111" stroke="#000" stroke-opacity=".15" stroke-width="10"/>
      <polygon points="185,160 185,205 225,182" fill="#000" fill-opacity=".15"/>""",
    "Livre": """
      <path d="M200 105 Q160 88 118 95 L118 235 Q160 228 200 245 Z" fill="#fff" fill-opacity=".9"/>
      <path d="M200 105 Q240 88 282 95 L282 235 Q240 228 200 245 Z" fill="#fff" fill-opacity=".75"/>
      <path d="M135 125 L185 130 M135 150 L185 155 M135 175 L185 180" stroke="#000" stroke-opacity=".12" stroke-width="4"/>""",
    "Beaute": """
      <rect x="183" y="82" width="34" height="34" rx="6" fill="#fff"/>
      <rect x="160" y="112" width="80" height="135" rx="22" fill="#fff" fill-opacity=".9"/>
      <rect x="175" y="150" width="50" height="55" rx="8" fill="#000" fill-opacity=".08"/>""",
}


def _color_key(doc: dict) -> str:
    a = doc.get("attributes", {})
    return a.get("artist") or a.get("serie") or a.get("marque") or a.get("auteur") or doc["category"]


def product_svg(doc: dict) -> str:
    c1, c2 = PALETTE[int(hashlib.md5(_color_key(doc).encode()).hexdigest(), 16) % len(PALETTE)]
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300" viewBox="0 0 400 300">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient>
    <filter id="s" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="6" stdDeviation="8" flood-color="#000" flood-opacity=".12"/></filter>
  </defs>
  <rect width="400" height="300" fill="url(#g)"/>
  <g transform="translate(0 -15)" filter="url(#s)">{ICONS[doc["category"]]}
  </g>
</svg>"""