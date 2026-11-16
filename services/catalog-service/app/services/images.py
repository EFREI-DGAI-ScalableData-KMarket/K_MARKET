"""Visuels SVG des produits, générés à la volée (aucun fichier stocké).

Couleurs déterministes : un même artiste / une même série / une même marque garde toujours le même dégradé.
Formes génériques par catégorie (pas de pochette ni de logo réels : droits d'auteur).
"""
import hashlib
from xml.sax.saxutils import escape

# Dégradés (haut-gauche -> bas-droite)
PALETTE = [
    ("#ff9a9e", "#fad0c4"), ("#a18cd1", "#fbc2eb"), ("#84fab0", "#8fd3f4"), ("#fccb90", "#d57eeb"),
    ("#e0c3fc", "#8ec5fc"), ("#f6d365", "#fda085"), ("#89f7fe", "#66a6ff"), ("#fbc2eb", "#a6c1ee"),
    ("#ff758c", "#ff7eb3"), ("#43e97b", "#38f9d7"), ("#fa709a", "#fee140"), ("#30cfd0", "#330867"),
    ("#667eea", "#764ba2"), ("#f093fb", "#f5576c"), ("#5ee7df", "#b490ca"), ("#c471f5", "#fa71cd"),
]

LABELS = {"Album": "Album", "Lightstick": "Lightstick", "Merch": "Merch",
          "Drama": "Drama", "Livre": "Livre", "Beaute": "Beauté"}

# Icône blanche centrée sur (200, 165), environ 150 px
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


def _subtitle(doc: dict) -> str:
    a = doc.get("attributes", {})
    return a.get("artist") or a.get("serie") or a.get("marque") or a.get("auteur") or ""


def _wrap(text: str, width: int = 26, max_lines: int = 2) -> list[str]:
    lines, current = [], ""
    for word in text.split():
        if len(current) + len(word) + 1 > width and current:
            lines.append(current)
            current = word
        else:
            current = f"{current} {word}".strip()
    lines.append(current)
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1][: width - 1].rstrip() + "…"
    return lines


def product_svg(doc: dict) -> str:
    category = doc["category"]
    key = _subtitle(doc) or category
    c1, c2 = PALETTE[int(hashlib.md5(key.encode()).hexdigest(), 16) % len(PALETTE)]
    name_lines = _wrap(doc["name"])
    name_svg = "".join(
        f'<text x="24" y="{300 + i * 28}" font-size="22" font-weight="700" fill="#1f1f2e">{escape(line)}</text>'
        for i, line in enumerate(name_lines))
    sub_y = 300 + len(name_lines) * 28 + 4
    price = f'{doc["price_cents"] / 100:.2f} €'.replace(".", ",")
    archived = ('<rect x="0" y="0" width="400" height="400" fill="#fff" fill-opacity=".45"/>'
                '<text x="376" y="48" text-anchor="end" font-size="14" font-weight="700" fill="#555">ARCHIVÉ</text>'
                if doc.get("status") == "inactive" else "")
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400" font-family="Helvetica, Arial, sans-serif">
  <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient></defs>
  <rect width="400" height="400" rx="24" fill="url(#g)"/>
  <rect x="20" y="20" width="{len(LABELS[category]) * 9 + 28}" height="30" rx="15" fill="#fff" fill-opacity=".85"/>
  <text x="34" y="41" font-size="14" font-weight="700" fill="#1f1f2e">{LABELS[category]}</text>
  {ICONS[category]}
  <rect x="0" y="268" width="400" height="132" fill="#fff" fill-opacity=".82"/>
  {name_svg}
  <text x="24" y="{sub_y + 16}" font-size="15" fill="#555">{escape(key if key != category else "")}</text>
  <text x="376" y="{sub_y + 16}" text-anchor="end" font-size="17" font-weight="700" fill="#1f1f2e">{price}</text>
  {archived}
</svg>"""