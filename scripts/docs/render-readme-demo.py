"""Render the illustrative README animation. Requires Pillow; no Hop runtime."""

import argparse
from functools import partial
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "docs/images"
W, H = 1120, 600
COLORS = {
    "paper": "#F2F7FB",
    "white": "#FFFFFF",
    "ink": "#173A50",
    "muted": "#526C7C",
    "line": "#CEDDE6",
    "blue": "#087EA4",
    "pale": "#E3F2F8",
    "green": "#087F75",
}


def font(size, bold=False):
    candidates = [
        Path("C:/Windows/Fonts") / ("seguisb.ttf" if bold else "segoeui.ttf"),
        Path("/usr/share/fonts/truetype/dejavu")
        / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    raise RuntimeError("Install Segoe UI or DejaVu Sans to render the demo.")


FONTS = {size: font(size) for size in (17, 19, 21, 23, 26, 34)}
BOLD = {size: font(size, True) for size in (19, 21, 23, 26, 34)}


ENGLISH = {
    "Tu asistente, conectado a Apache Hop": "Your AI assistant, connected to Apache Hop",
    "MCP normal": "Local MCP",
    "Live Sync Desktop": "Desktop Live Sync",
    "Live Sync Web": "Web Live Sync",
    "Explícame este proyecto": "Explain this project",
    "y sus dependencias.": "and its dependencies.",
    "Proyecto local": "Local project",
    "Consulta desde tu cliente MCP, sin abrir el editor.": "Inspect from your MCP client, without opening the editor.",
    "Busca archivos · Consulta metadatos · Revisa dependencias": "Find files · Inspect metadata · Explore dependencies",
    "Crea un pipeline": "Create a pipeline",
    "y muéstrame los pasos.": "and show me the steps.",
    "Editor Desktop": "Desktop editor",
    "Sigue los cambios en el editor nativo de Hop.": "Follow changes in Hop's native editor.",
    "Construcción guiada · Pausa · Aplicación instantánea": "Guided construction · Pause · Instant application",
    "Conéctate a Hop Web": "Connect to Hop Web",
    "y edita este flujo.": "and edit this flow.",
    "Sesión Web": "Web session",
    "Conecta por URL al proyecto de la sesión activa.": "Connect by URL to the active session's project.",
    "Sin carpetas compartidas · Sesión elegida por el usuario": "No shared folders · User-selected session",
    "Asistente de IA": "AI assistant",
    "Tu instrucción": "Your instruction",
    "Conector Hop": "Hop connector",
    "ventas.hpl": "sales.hpl",
    "carga_diaria.hwf": "daily_load.hwf",
    "Metadatos y dependencias": "Metadata and dependencies",
    "Construcción guiada": "Guided construction",
    "Leer": "Read",
    "Preparar": "Prepare",
    "Guardar": "Save",
    "Ilustración del funcionamiento. Live Sync requiere activación; la edición local requiere permiso.": "Conceptual illustration. Enable Live Sync first; local editing requires permission.",
}


def text(draw, xy, value, size=21, color="ink", bold=False, anchor=None, language="es"):
    if language == "en":
        value = ENGLISH.get(value, value)
    draw.text(
        xy,
        value,
        font=(BOLD if bold else FONTS)[size],
        fill=COLORS.get(color, color),
        anchor=anchor,
    )


def box(draw, coords, fill="white", outline="line", radius=14, width=2):
    draw.rounded_rectangle(
        coords,
        radius=radius,
        fill=COLORS[fill],
        outline=COLORS[outline],
        width=width,
    )


def arrow(draw, start, end, progress=0):
    draw.line([start, end], fill=COLORS["line"], width=4)
    x, y = end
    draw.polygon([(x, y), (x - 9, y - 6), (x - 9, y + 6)], fill=COLORS["blue"])
    if progress > 0:
        x = start[0] + (end[0] - start[0]) * progress
        draw.ellipse((x - 6, y - 6, x + 6, y + 6), fill=COLORS["blue"])


SCENES = [
    (
        "MCP normal",
        ["Explícame este proyecto", "y sus dependencias."],
        "Proyecto local",
        "Consulta desde tu cliente MCP, sin abrir el editor.",
        "Busca archivos · Consulta metadatos · Revisa dependencias",
    ),
    (
        "Live Sync Desktop",
        ["Crea un pipeline", "y muéstrame los pasos."],
        "Editor Desktop",
        "Sigue los cambios en el editor nativo de Hop.",
        "Construcción guiada · Pausa · Aplicación instantánea",
    ),
    (
        "Live Sync Web",
        ["Conéctate a Hop Web", "y edita este flujo."],
        "Sesión Web",
        "Conecta por URL al proyecto de la sesión activa.",
        "Sin carpetas compartidas · Sesión elegida por el usuario",
    ),
]


def pipeline(draw, y, progress, language):
    write = partial(text, language=language)
    labels = ["Leer", "Preparar", "Guardar"]
    for i, label in enumerate(labels):
        x = 774 + i * 110
        ready = progress >= (i + 1) * 0.18
        if i:
            draw.line((x - 62, y + 23, x - 26, y + 23), fill=COLORS["blue"] if ready else COLORS["line"], width=3)
        box(draw, (x - 24, y, x + 24, y + 46), fill="pale" if ready else "white", outline="blue" if ready else "line", radius=9)
        if ready:
            draw.line((x - 9, y + 23, x - 2, y + 30, x + 11, y + 16), fill=COLORS["blue"], width=3)
        write(draw, (x, y + 58), label, 19, anchor="mt")


def render(scene, progress, language):
    write = partial(text, language=language)
    image = Image.new("RGB", (W, H), COLORS["paper"])
    draw = ImageDraw.Draw(image)
    name, prompt, target, subtitle, features = SCENES[scene]
    write(draw, (40, 28), "Tu asistente, conectado a Apache Hop", 34, bold=True)
    for i, (label, *_) in enumerate(SCENES):
        x = 40 + i * 348
        box(draw, (x, 94, x + 332, 141), fill="blue" if i == scene else "paper", outline="blue" if i == scene else "line", radius=10)
        write(draw, (x + 166, 117), label, 21, color="white" if i == scene else "muted", bold=True, anchor="mm")

    box(draw, (40, 204, 338, 399))
    write(draw, (64, 224), "Asistente de IA", 23, bold=True)
    write(draw, (64, 275), prompt[0], 21)
    write(draw, (64, 308), prompt[1], 21)
    write(draw, (64, 361), "Tu instrucción", 17, "muted")

    box(draw, (430, 239, 606, 365), fill="blue", outline="blue")
    write(draw, (518, 271), "MCP", 34, "white", True, "mt")
    write(draw, (518, 323), "Conector Hop", 19, "white", anchor="mt")

    motion = (progress * 2.3) % 1
    arrow(draw, (352, 302), (416, 302), motion)
    arrow(draw, (620, 302), (693, 302), motion)

    box(draw, (707, 184, 1080, 420))
    draw.line((708, 236, 1078, 236), fill=COLORS["line"], width=2)
    write(draw, (729, 199), target, 23, bold=True)
    if scene == 0:
        for i, label in enumerate(["ventas.hpl", "carga_diaria.hwf", "Metadatos y dependencias"]):
            y = 259 + i * 48
            ready = progress >= i * 0.18
            box(draw, (729, y, 1058, y + 39), fill="pale" if ready else "white", outline="pale" if ready else "white", radius=7)
            write(draw, (743, y + 6), label, 19, "ink" if ready else "muted")
    else:
        if scene == 2:
            write(draw, (729, 252), "https://hop.example.org", 19, "muted")
        else:
            write(draw, (729, 252), "Construcción guiada", 19, "muted")
        pipeline(draw, 297, progress, language)

    write(draw, (40, 457), subtitle, 26, bold=True)
    write(draw, (40, 505), features, 21, "muted")
    draw.line((40, 552, 1080, 552), fill=COLORS["line"], width=1)
    write(draw, (40, 566), "Ilustración del funcionamiento. Live Sync requiere activación; la edición local requiere permiso.", 17, "muted")
    return image


def export(language):
    DEST.mkdir(parents=True, exist_ok=True)
    frames = []
    durations = []
    # Ten frames per second, with a quiet final hold in each scene.
    for scene in range(3):
        for frame in range(30):
            frames.append(render(scene, min(frame / 18, 1), language))
            durations.append(100)
        frames.append(render(scene, 1, language))
        durations.append(900)
    # A shared palette avoids color flicker between frames.
    palette = Image.new("RGB", (W, H * 3))
    for scene in range(3):
        palette.paste(render(scene, 1, language), (0, scene * H))
    palette = palette.quantize(colors=96)
    frames = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in frames]
    name = "mcp-demo-en" if language == "en" else "mcp-demo"
    frames[0].save(DEST / f"{name}.gif", save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True)
    render(1, 1, language).save(DEST / f"{name}.png", optimize=True)
    print(f"{name}.gif: {(DEST / (name + '.gif')).stat().st_size:,} bytes; {sum(durations) / 1000:.1f} seconds")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("en", "es", "all"), default="all")
    language = parser.parse_args().language
    for selected in ("en", "es") if language == "all" else (language,):
        export(selected)


if __name__ == "__main__":
    main()
