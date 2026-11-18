from math import cos, pi, sin
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.platypus import (
    Flowable,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

OUTPUT = Path("VectorForge_Master_Project_Explainer.pdf")
INK = colors.HexColor("#14241C")
GREEN = colors.HexColor("#397153")
LIME = colors.HexColor("#D9F36A")
PAPER = colors.HexColor("#F5F5EE")
PALE_GREEN = colors.HexColor("#E6EEE1")
PALE_BLUE = colors.HexColor("#E5EDF3")
PALE_YELLOW = colors.HexColor("#F5EFD9")
CORAL = colors.HexColor("#D47D62")
MUTED = colors.HexColor("#68766D")
LINE = colors.HexColor("#D6DDD4")


def wrapped_lines(text, font, size, width):
    words = text.split()
    lines = []
    current = ""
    for word in words:
        candidate = word if not current else current + " " + word
        if stringWidth(candidate, font, size) <= width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def draw_text(c, text, x, y, width, size=7.5, leading=9, color=INK, font="Helvetica", max_lines=None):
    c.setFont(font, size)
    c.setFillColor(color)
    lines = wrapped_lines(text, font, size, width)
    if max_lines:
        lines = lines[:max_lines]
    for line in lines:
        c.drawString(x, y, line)
        y -= leading
    return y


def arrow(c, x1, y1, x2, y2, color=GREEN, width=1.2):
    c.setStrokeColor(color)
    c.setFillColor(color)
    c.setLineWidth(width)
    c.line(x1, y1, x2, y2)
    angle = __import__("math").atan2(y2 - y1, x2 - x1)
    size = 5
    c.line(x2, y2, x2 - size * cos(angle - 0.45), y2 - size * sin(angle - 0.45))
    c.line(x2, y2, x2 - size * cos(angle + 0.45), y2 - size * sin(angle + 0.45))


def box(c, x, y, width, height, title, subtitle="", fill=colors.white, accent=GREEN, title_size=8.5):
    c.setFillColor(fill)
    c.setStrokeColor(LINE)
    c.setLineWidth(0.8)
    c.roundRect(x, y, width, height, 4, fill=1, stroke=1)
    c.setFillColor(accent)
    c.roundRect(x, y, 4, height, 2, fill=1, stroke=0)
    draw_text(c, title, x + 10, y + height - 15, width - 18, size=title_size, leading=10, font="Helvetica-Bold")
    if subtitle:
        draw_text(c, subtitle, x + 10, y + height - 29, width - 18, size=6.7, leading=8, color=MUTED)


class Diagram(Flowable):
    def __init__(self, kind, height):
        super().__init__()
        self.kind = kind
        self.height = height
        self.width = 520

    def wrap(self, available_width, available_height):
        self.width = available_width
        return self.width, self.height

    def draw(self):
        canvas = self.canv
        if self.kind == "architecture":
            self.draw_architecture(canvas)
        elif self.kind == "write":
            self.draw_write(canvas)
        elif self.kind == "ring":
            self.draw_ring(canvas)
        elif self.kind == "storage":
            self.draw_storage(canvas)
        elif self.kind == "hnsw":
            self.draw_hnsw(canvas)
        elif self.kind == "query":
            self.draw_query(canvas)
        elif self.kind == "benchmark":
            self.draw_benchmark(canvas)

    def draw_architecture(self, c):
        w = self.width
        box(c, w * .29, self.height - 50, w * .42, 35, "BROWSER CONSOLE", "React UI / Transformers.js / IndexedDB", PALE_GREEN, GREEN, 9)
        box(c, w * .29, self.height - 105, w * .42, 35, "HTTP GATEWAY + COORDINATOR", "JSON API / consistent hash ring / quorum writes", PALE_YELLOW, CORAL, 8.5)
        arrow(c, w * .5, self.height - 51, w * .5, self.height - 72)
        xs = [w * .035, w * .355, w * .675]
        labels = ["SHARD 1 / :9101", "SHARD 2 / :9102", "SHARD 3 / :9103"]
        for x, label in zip(xs, labels):
            box(c, x, 35, w * .29, 85, label, "HNSW index\nWAL + memtable\nSSTable + compaction", PALE_BLUE, GREEN, 8)
            arrow(c, w * .5, self.height - 106, x + w * .145, 121, CORAL)
        c.setFillColor(MUTED)
        c.setFont("Helvetica-Oblique", 7)
        c.drawCentredString(w / 2, 17, "Three independent Java processes; each vector is stored on two distinct shards.")

    def draw_write(self, c):
        w = self.width
        gap = 12
        x0 = 3
        bw = (w - 6 - gap * 4) / 5
        y = self.height - 75
        items = [
            ("TEXT", "Browser input", PALE_GREEN),
            ("EMBED", "384D vector", PALE_BLUE),
            ("HASH ID", "Choose 2 shards", PALE_YELLOW),
            ("WAL + FSYNC", "Durable first", colors.HexColor("#F4E5DE")),
            ("MEMTABLE + HNSW", "Serve and index", PALE_GREEN),
        ]
        for i, (title, sub, fill) in enumerate(items):
            x = x0 + i * (bw + gap)
            box(c, x, y, bw, 48, title, sub, fill, GREEN, 7.2)
            if i < len(items) - 1:
                arrow(c, x + bw + 2, y + 24, x + bw + gap - 2, y + 24)
        box(c, w * .24, 24, w * .52, 38, "QUORUM ACK", "With replication factor 2, both selected replicas must acknowledge.", INK, LIME, 8.5)
        c.setFillColor(colors.white)
        c.setFont("Helvetica", 7)
        c.drawCentredString(w / 2, 47, "QUORUM ACK")
        c.setFont("Helvetica", 6.7)
        c.drawCentredString(w / 2, 33, "2 durable shard writes -> client success")
        arrow(c, w * .5, y - 4, w * .5, 64, GREEN)
        c.setFillColor(MUTED)
        c.setFont("Helvetica-Oblique", 7)
        c.drawCentredString(w / 2, 9, "When a memtable reaches 64 MiB, its snapshot flushes asynchronously to an SSTable.")

    def draw_ring(self, c):
        w = self.width
        cx, cy, radius = w * .40, self.height * .53, min(self.height * .39, w * .27)
        c.setStrokeColor(LINE)
        c.setLineWidth(7)
        c.circle(cx, cy, radius, fill=0, stroke=1)
        palette = [GREEN, CORAL, colors.HexColor("#6B8EBC")]
        for i in range(30):
            angle = 2 * pi * i / 30
            x, y = cx + radius * cos(angle), cy + radius * sin(angle)
            c.setFillColor(palette[(i * 7 // 30) % 3])
            c.circle(x, y, 4, fill=1, stroke=0)
        key_angle = 0.42
        kx, ky = cx + (radius + 18) * cos(key_angle), cy + (radius + 18) * sin(key_angle)
        c.setFillColor(CORAL)
        c.circle(kx, ky, 5, fill=1, stroke=0)
        c.setFillColor(INK)
        c.setFont("Helvetica-Bold", 7)
        c.drawString(kx + 7, ky + 2, "vector ID")
        c.setStrokeColor(GREEN)
        c.setLineWidth(1.5)
        c.arc(cx - radius - 8, cy - radius - 8, cx + radius + 8, cy + radius + 8, 12, 55)
        arrow(c, cx + radius * .88, cy + radius * .42, cx + radius * .94, cy + radius * .28)
        lx = w * .73
        c.setFillColor(INK)
        c.setFont("Helvetica-Bold", 9)
        c.drawString(lx, self.height - 43, "OWNER SELECTION")
        legends = [(GREEN, "Node 1 virtual nodes"), (CORAL, "Node 2 virtual nodes"), (colors.HexColor("#6B8EBC"), "Node 3 virtual nodes")]
        for i, (color, text) in enumerate(legends):
            yy = self.height - 67 - i * 20
            c.setFillColor(color)
            c.circle(lx + 4, yy + 2, 4, fill=1, stroke=0)
            c.setFillColor(MUTED)
            c.setFont("Helvetica", 7.5)
            c.drawString(lx + 14, yy, text)
        draw_text(c, "The ring hashes the ID, walks clockwise, and selects distinct shard owners. The current factor is 2.", lx, 73, w * .25, size=7, leading=10, color=MUTED)

    def draw_storage(self, c):
        w = self.width
        items = [
            ("WAL", "Append + fsync", PALE_YELLOW),
            ("MEMTABLE", "Sorted in memory", PALE_BLUE),
            ("SSTABLE L0", "Immutable disk run", PALE_GREEN),
            ("COMPACTION", "Merge / deduplicate", colors.HexColor("#EDE4F0")),
        ]
        gap = 22
        bw = (w - 20 - gap * 3) / 4
        y = self.height - 76
        for i, (title, sub, fill) in enumerate(items):
            x = 10 + i * (bw + gap)
            box(c, x, y, bw, 47, title, sub, fill, GREEN, 8)
            if i < len(items) - 1:
                arrow(c, x + bw + 2, y + 23, x + bw + gap - 2, y + 23)
        arrow(c, 10 + bw * 1.5, y - 3, 10 + bw * 1.5, 42, CORAL)
        box(c, 10, 10, w - 20, 34, "CRASH RECOVERY", "Load SSTables -> replay WAL records newer than checkpoint -> rebuild memtable and HNSW.", INK, LIME, 8)
        c.setFillColor(colors.white)
        c.setFont("Helvetica-Bold", 8)
        c.drawString(20, 29, "CRASH RECOVERY")
        draw_text(c, "Load SSTables -> replay WAL after checkpoint -> rebuild memory indexes.", 117, 29, w - 145, size=7, leading=8, color=colors.white)
        c.setFillColor(MUTED)
        c.setFont("Helvetica-Oblique", 6.8)
        c.drawCentredString(w / 2, 1, "Current threshold: 64 MiB per memtable; compaction checks when L0 reaches four files.")

    def draw_hnsw(self, c):
        w = self.width
        left = 93
        rows = [(self.height - 43, [190, 310, 430]), (self.height - 102, [142, 215, 288, 361, 434]), (self.height - 166, [110, 160, 210, 260, 310, 360, 410, 460])]
        labels = ["LAYER 2 / sparse", "LAYER 1 / medium", "LAYER 0 / all vectors"]
        for row_index, (y, positions) in enumerate(rows):
            c.setFillColor(MUTED)
            c.setFont("Helvetica-Bold", 7)
            c.drawString(3, y - 2, labels[row_index])
            if row_index < len(rows) - 1:
                next_y, next_positions = rows[row_index + 1]
                for i, x in enumerate(positions):
                    nx = next_positions[min(len(next_positions) - 1, i * len(next_positions) // len(positions))]
                    c.setStrokeColor(LINE)
                    c.line(left + x * .2, y - 8, left + nx * .2, next_y + 8)
            for i, x in enumerate(positions):
                c.setFillColor(LIME if row_index == 0 and i == 0 else GREEN)
                c.circle(left + x * .2, y, 7 if row_index < 2 else 5, fill=1, stroke=0)
                if i < len(positions) - 1:
                    c.setStrokeColor(colors.HexColor("#93AA86"))
                    c.line(left + x * .2 + 5, y, left + positions[i + 1] * .2 - 5, y)
        c.setFillColor(MUTED)
        c.setFont("Helvetica", 7)
        c.drawString(w * .68, self.height - 42, "1. Start at the entry point")
        c.drawString(w * .68, self.height - 58, "2. Greedy descent across layers")
        c.drawString(w * .68, self.height - 74, "3. Beam search at layer zero")
        c.drawString(w * .68, self.height - 90, "4. Return nearest K candidates")
        box(c, w * .68, 23, w * .28, 34, "ef_search", "More candidates -> more recall / work", PALE_YELLOW, CORAL, 8)
        c.setFillColor(MUTED)
        c.setFont("Helvetica-Oblique", 7)
        c.drawCentredString(w / 2, 8, "Higher ef_search tends to improve recall while increasing query latency.")

    def draw_query(self, c):
        w = self.width
        box(c, 5, self.height - 48, 120, 35, "BROWSER QUERY", "Text -> 384D embedding", PALE_GREEN, GREEN, 8)
        box(c, w - 125, self.height - 48, 120, 35, "GLOBAL TOP-K", "Deduplicate + sort by L2", INK, LIME, 8)
        c.setFillColor(colors.white)
        c.setFont("Helvetica-Bold", 8)
        c.drawString(w - 113, self.height - 30, "GLOBAL TOP-K")
        c.setFont("Helvetica", 6.7)
        c.drawString(w - 113, self.height - 42, "Deduplicate + sort by L2")
        box(c, w * .37, self.height - 48, 135, 35, "COORDINATOR", "Scatter + merge", PALE_YELLOW, CORAL, 8)
        arrow(c, 128, self.height - 30, w * .37 - 3, self.height - 30)
        arrow(c, w * .37 + 138, self.height - 30, w - 128, self.height - 30)
        for i, label in enumerate(["SHARD 1", "SHARD 2", "SHARD 3"]):
            x = 12 + i * (w * .32)
            box(c, x, 30, w * .28, 58, label, "Local Java HNSW -> top-K", PALE_BLUE, GREEN, 8)
            arrow(c, w * .44, self.height - 51, x + w * .14, 90, GREEN)
            arrow(c, x + w * .28, 59, w - 126, self.height - 51, CORAL)
        c.setFillColor(MUTED)
        c.setFont("Helvetica-Oblique", 7)
        c.drawCentredString(w / 2, 13, "Exact ground truth uses Java BruteForceIndex on shard data; current backend schedules those shard calls sequentially.")

    def draw_benchmark(self, c):
        w = self.width
        rows = [
            ("Query vectors", "same held-out sample"),
            ("Exact baseline", "Java BruteForceIndex"),
            ("Approximate search", "Java HNSW at ef = 10..300"),
            ("Compare", "Recall@K + end-to-end latency"),
        ]
        left = 12
        yy = self.height - 39
        for i, (title, subtitle) in enumerate(rows):
            y = yy - i * 38
            fill = [PALE_GREEN, PALE_YELLOW, PALE_BLUE, colors.HexColor("#F3E4DE")][i]
            box(c, left, y - 20, w * .51, 29, title, subtitle, fill, GREEN, 7.5)
            if i < len(rows) - 1:
                arrow(c, left + w * .255, y - 21, left + w * .255, y - 34, GREEN)
        c.setFillColor(INK)
        c.setFont("Helvetica-Bold", 9)
        c.drawString(w * .63, self.height - 47, "READ THE RESULT")
        draw_text(c, "Recall is set overlap: found true neighbors divided by K. Speedup is exact latency / HNSW latency.", w * .63, self.height - 64, w * .34, size=7.5, leading=10, color=MUTED)
        draw_text(c, "For a fair algorithm-only comparison, run both methods with equivalent scheduling. Current HNSW fans out concurrently; exact shard calls are sequential.", w * .63, self.height - 112, w * .34, size=7.2, leading=9, color=CORAL)


def build_pdf():
    page_width, page_height = letter
    styles = getSampleStyleSheet()
    title = ParagraphStyle(
        "VFTitle", parent=styles["Title"], fontName="Helvetica-Bold", fontSize=29,
        leading=33, alignment=TA_CENTER, textColor=INK, spaceAfter=12,
    )
    subtitle = ParagraphStyle(
        "VFSubtitle", parent=styles["Normal"], fontSize=12, leading=17,
        alignment=TA_CENTER, textColor=MUTED, spaceAfter=14,
    )
    h1 = ParagraphStyle(
        "VFSection", parent=styles["Heading1"], fontName="Helvetica-Bold",
        fontSize=19, leading=23, textColor=INK, spaceBefore=4, spaceAfter=7,
    )
    h2 = ParagraphStyle(
        "VFSubsection", parent=styles["Heading2"], fontName="Helvetica-Bold",
        fontSize=12, leading=15, textColor=GREEN, spaceBefore=7, spaceAfter=5,
    )
    body = ParagraphStyle(
        "VFBody", parent=styles["BodyText"], fontSize=9.2, leading=13,
        textColor=INK, spaceAfter=6,
    )
    bullet = ParagraphStyle(
        "VFBullet", parent=body, leftIndent=16, firstLineIndent=-8, spaceAfter=4,
    )
    callout = ParagraphStyle(
        "VFCallout", parent=body, fontName="Helvetica-Bold", fontSize=10,
        leading=14, textColor=INK, backColor=PALE_GREEN, borderColor=GREEN,
        borderWidth=1, borderPadding=8, spaceBefore=5, spaceAfter=8,
    )
    small = ParagraphStyle(
        "VFSmall", parent=body, fontSize=7.7, leading=10, textColor=MUTED,
    )
    code = ParagraphStyle(
        "VFCode", parent=body, fontName="Courier", fontSize=7.2, leading=9,
        backColor=colors.HexColor("#EEF1E9"), borderPadding=6,
        leftIndent=8, rightIndent=8, spaceAfter=7,
    )
    doc = SimpleDocTemplate(
        str(OUTPUT), pagesize=letter, rightMargin=44, leftMargin=44,
        topMargin=43, bottomMargin=43,
        title="VectorForge Master Project Explainer",
        author="VectorForge Project",
    )
    story = []

    def p(text, style=body):
        story.append(Paragraph(text, style))

    def bullets(items):
        for item in items:
            p("- " + item, bullet)

    def make_table(rows, widths, font_size=8.1):
        cell = ParagraphStyle("VFCell", parent=body, fontSize=font_size, leading=font_size + 2)
        head = ParagraphStyle("VFHead", parent=cell, fontName="Helvetica-Bold", textColor=colors.white)
        data = [[Paragraph(str(value), head if row_index == 0 else cell) for value in row]
                for row_index, row in enumerate(rows)]
        table = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
        table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), INK),
            ("GRID", (0, 0), (-1, -1), .4, LINE),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, PAPER]),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ]))
        story.append(table)
        story.append(Spacer(1, 8))

    # Cover
    story.append(Spacer(1, .45 * inch))
    p("VECTORFORGE", title)
    p("A Visual Master Guide to the Project", subtitle)
    p("How text becomes embeddings, how Java stores and searches vectors, and how shards cooperate.", subtitle)
    story.append(Spacer(1, .12 * inch))
    story.append(Diagram("architecture", 240))
    p("THE 20-SECOND EXPLANATION", h2)
    p("VectorForge is a learning-scale distributed vector database. A browser creates an embedding for text; Java shards persist and index the vector; a coordinator routes replicated writes and merges nearest-neighbor results.", callout)
    make_table([
        ["This system currently does", "This is still future work"],
        ["Java WAL, memtable, SSTable flush/compaction, HNSW, brute-force oracle, consistent hash routing, two-copy replication, quorum writes, multi-process HTTP shards, React UI.", "gRPC, automatic leader promotion/failover, leases, production-grade network/security, SIMD being selected in the hot path, SQ8/PQ in the serving index."],
    ], [3.57 * inch, 3.57 * inch])
    p("Prepared October 2026 | Use this as a project walkthrough, not as a claim of production readiness.", small)
    story.append(PageBreak())

    # 1 system view
    p("1. System at a Glance", h1)
    p("There are five layers. Each has one job, and the boundaries make it possible to explain and test the system piece by piece.")
    story.append(Diagram("architecture", 240))
    make_table([
        ["Layer", "What it owns", "Simple explanation"],
        ["Browser console", "Source text, UI state, local embedding worker", "Turns text into a 384-number vector and presents results."],
        ["HTTP Gateway", "JSON endpoints for writes, queries, deletes, clear, and stats", "The front door for the browser and SDK."],
        ["Coordinator", "Hash ring, replica routing, quorum, scatter/gather", "Chooses write owners and merges query answers."],
        ["Shard node", "One WAL, memtable, HNSW, brute-force oracle, SSTable directory", "A storage/search worker with its own durable state."],
        ["Storage files", "WAL segments, checkpoint, immutable SSTables", "The on-disk recovery trail."],
    ], [1.25 * inch, 2.75 * inch, 3.14 * inch])
    p("Implementation detail: `HttpGateway` runs on port 8080. The current separate-process setup runs three `ShardNodeGateway` processes on 9101, 9102, and 9103. The coordinator connects to them with Java HTTP backends.", small)
    story.append(PageBreak())

    # 2 embedding + write path
    p("2. From Text to a Durable Vector", h1)
    p("One document means one text record and one embedding vector. The model tokenizes the text internally; VectorForge stores one 384-dimensional vector per document, not one vector per word.")
    story.append(Diagram("write", 145))
    p("What the browser does", h2)
    bullets([
        "The React app stores text in IndexedDB so search results can show original content.",
        "A Web Worker runs `Xenova/all-MiniLM-L6-v2` via Transformers.js and ONNX Runtime WebAssembly. The worker applies mean pooling and normalization.",
        "The worker keeps the UI responsive; the first browser use downloads/caches model assets. Java does not run the language model.",
    ])
    p("What a shard does", h2)
    bullets([
        "Append a WAL record and flush it before acknowledging the write.",
        "Put the latest record into a concurrent sorted memtable.",
        "Insert the vector into the custom Java HNSW graph and the Java exact-search index.",
        "When the memtable reaches 64 MiB, queue an asynchronous SSTable flush.",
    ])
    p("Reliability detail: replication factor is 2. The coordinator’s quorum formula is majority-of-targets; with two selected replicas, that means both must acknowledge. This configuration does not accept a write if one of those two replicas is unavailable.", callout)
    story.append(PageBreak())

    # 3 sharding
    p("3. Sharding and Replication", h1)
    p("VectorForge shards by ID, not by vector similarity. An ID is hashed onto a ring with 150 virtual positions per physical node; walking clockwise selects distinct replica owners.")
    story.append(Diagram("ring", 230))
    p("Why every query visits every shard", h2)
    p("A query vector is not tied to one ID. Any shard could contain a close neighbor, so the coordinator scatters each query to all three shards. Each shard returns candidates; the coordinator merges and deduplicates by ID before sending the global top K.")
    make_table([
        ["Term", "Meaning here"],
        ["Logical vector", "One user document / ID."],
        ["Physical copy", "One shard's persisted and indexed copy."],
        ["Replication factor 2", "Each logical upsert is sent to two distinct shard owners."],
        ["Per-shard vector count", "Physical active vectors on that shard; summing shards includes replicas."],
    ], [1.8 * inch, 5.34 * inch])
    p("The current code has no primary/standby roles or automatic promotion. The hash ring selects replica owners; it is not yet a full failover controller.", small)
    story.append(PageBreak())

    # 4 storage
    p("4. Storage, Flush, and Recovery", h1)
    story.append(Diagram("storage", 150))
    p("WAL: the Write-Ahead Log", h2)
    p("Each shard appends a serialized operation to its own segment. The write is flushed before acknowledgement. Segment rotation is configured at 64 MiB. A checkpoint records the last flushed sequence so recovery can replay only later WAL operations; eligible older segments are deleted.")
    p("Memtable and SSTable", h2)
    bullets([
        "The memtable is an in-memory sorted map keyed by document ID. Repeated IDs replace the previous in-memory record.",
        "An SSTable is immutable and contains data records, a sparse index, and a Bloom filter.",
        "Flush work is sent to a per-shard executor. Four or more ordinary L0 SSTables trigger the current compaction check.",
        "Recovery loads SSTable records, then replays WAL records newer than the checkpoint to rebuild memory state and HNSW.",
    ])
    p("Important honesty note: current vector search is served from the in-memory HNSW index. SSTables participate in persistence/recovery/compaction, but the project is not yet a complete disk-backed LSM point-read implementation.", callout)
    story.append(PageBreak())

    # 5 HNSW
    p("5. How the Custom HNSW Search Works", h1)
    p("HNSW means Hierarchical Navigable Small World. It is a graph of vectors with several layers: sparse upper layers provide long jumps, while layer zero contains all indexed vectors.")
    story.append(Diagram("hnsw", 220))
    make_table([
        ["Setting", "Current default", "Trade-off"],
        ["M", "16", "Maximum neighbor connections; more edges can raise recall and memory cost."],
        ["efConstruction", "200", "Candidate breadth while inserting; affects graph build quality and speed."],
        ["ef_search", "100", "Query exploration breadth; larger usually raises recall and latency."],
        ["Distance", "Squared L2", "Avoids square root while preserving distance ordering."],
    ], [1.35 * inch, 1.25 * inch, 4.54 * inch])
    p("A search returns approximate nearest neighbors. Recall@K measures overlap with exact Java brute force: if K=10 and eight returned IDs are in the exact top ten, recall@10 is 0.8.", callout)
    p("The HNSW implementation is custom Java code; the browser model only creates embeddings. No Python ANN library is used for the database query path.", small)
    story.append(PageBreak())

    # 6 query and benchmark
    p("6. Query and Benchmark Flow", h1)
    story.append(Diagram("query", 190))
    p("The benchmark", h2)
    story.append(Diagram("benchmark", 190))
    bullets([
        "The UI embeds a held-out subset of locally cached texts in the browser.",
        "Exact results come from Java `BruteForceIndex` on each shard; HNSW results come from Java HNSW on each shard.",
        "Recall@K compares HNSW IDs to exact IDs. Latency is measured across the HTTP request path and coordinator merge.",
        "The current HNSW coordinator fans shard queries out concurrently; the brute-force coordinator path currently calls shard baselines sequentially. Therefore the chart reflects current system scheduling as well as index algorithms. It is not a perfectly matched algorithm-only microbenchmark.",
    ])
    p("For a teacher/demo: say what is measured and mention the scheduling caveat. Do not present a single speedup as a universal HNSW guarantee.", callout)
    story.append(PageBreak())

    # 7 interface / api
    p("7. Browser UI and Java API", h1)
    make_table([
        ["UI page", "What a user can do"],
        ["Overview", "See local text count, physical shard vector count, shard health, and memtable/SSTable stats."],
        ["Documents", "Single insert, TXT/CSV bulk import, paste import, filter, delete, cluster-wide clear, CSV export."],
        ["Search", "Embed a natural-language query in the browser and inspect Java HNSW IDs, original text, and L2 distances."],
        ["Benchmark", "Sweep `ef_search`; display exact baseline, recall, average latency, speedup, and a recall/latency plot."],
    ], [1.35 * inch, 5.79 * inch])
    make_table([
        ["HTTP endpoint", "Purpose"],
        ["POST /upsert", "Route and replicate an ID + vector."],
        ["POST /query", "Coordinator HNSW scatter/gather; accepts vector, top_k, ef_search."],
        ["POST /bruteforce/query", "Exact Java benchmark baseline."],
        ["POST /delete", "Replicated tombstone for one ID."],
        ["POST /v1/cluster/clear", "Durably tombstone active vectors on every shard."],
        ["GET /v1/cluster/nodes", "Live per-shard health, memtable bytes, SSTable count, vector count."],
        ["POST /upsert, /query, /delete, /clear on shard ports", "Coordinator-to-shard HTTP transport."],
    ], [2.7 * inch, 4.44 * inch])
    p("The local browser cache holds original text; the Java cluster holds active vectors. A cluster clear and a local-cache clear are coordinated by the UI, and startup checks server stats to avoid resurrecting old browser records after an empty-cluster reset.", body)

    # 8 efficiency
    p("8. Where Efficiency Comes From", h1)
    make_table([
        ["Work", "Technique", "Benefit"],
        ["Durable writes", "Append-only WAL + fsync", "Sequential persistence and deterministic replay."],
        ["Buffering", "Sorted memtable", "Consolidates recent writes before disk-file creation."],
        ["Disk runs", "Immutable SSTable + sparse index + Bloom filter", "Compact sequential files and faster negative lookups."],
        ["Approximate query", "Layered HNSW + ef_search", "Explores a bounded candidate neighborhood rather than scanning every vector."],
        ["Cluster query", "Parallel shard fan-out + deduplicated top-K merge", "Uses independent shard work and returns only requested results."],
        ["Embedding/UI", "Browser worker + WASM", "Embedding does not block Java services or the interface thread."],
    ], [1.35 * inch, 2.55 * inch, 3.24 * inch])
    p("Available but not currently selected on the live serving path", h2)
    bullets([
        "Java SIMD distance code exists, but ShardNode currently constructs scalar `L2Distance`.",
        "SQ8/PQ quantization code is not wired into the active HNSW storage/search path.",
        "gRPC, automatic failover, health heartbeats/leases, TLS/authentication, and production backpressure remain future work.",
    ])
    story.append(PageBreak())

    # 9 status
    p("9. Current Status: Implemented vs. Planned", h1)
    make_table([
        ["Capability", "Status", "What to say"],
        ["Three separate Java shard processes", "Implemented", "Independent HTTP service and per-process data directories."],
        ["ID hash ring / 150 virtual nodes", "Implemented", "Routes writes to distinct shard owners."],
        ["Two-copy replication and quorum writes", "Implemented", "Both selected copies must succeed at factor 2."],
        ["Java HNSW and Java brute-force oracle", "Implemented", "Custom HNSW; exact baseline for recall."],
        ["WAL, memtable, SSTable flush, compaction, replay", "Implemented", "Active write path and restart recovery mechanisms."],
        ["Cluster-wide tombstone clear", "Implemented", "Clears active in-memory indexes with durable WAL deletes."],
        ["gRPC typed transport", "Planned", "Current inter-process transport is HTTP/JSON."],
        ["Primary promotion / lease fencing", "Planned", "No automatic failover controller yet."],
        ["SIMD / SQ8 active index mode", "Planned", "Code/modules are not selected in production query path."],
        ["Matched-scheduling benchmark", "Needs improvement", "Parallelize exact baseline shard calls for a fairer algorithm comparison."],
    ], [2.05 * inch, 1.05 * inch, 4.04 * inch])
    p("Current demonstration state", h2)
    p("After the October 2026 clear operation: 3 healthy shards, 0 active vector counts, and 0 browser document texts. WAL files still retain historic bytes plus tombstones until checkpoint/compaction policy reclaims them; logical delete is not secure physical erasure.", callout)

    # 10 demo guide
    p("10. Teacher Walkthrough", h1)
    p("A short presentation can follow this order:", body)
    make_table([
        ["Step", "Say / show"],
        ["1", "Start with the architecture diagram: browser -> gateway -> coordinator -> three Java shards."],
        ["2", "Insert one sentence. Explain: browser model makes 384 numbers; Java WAL makes the write durable; coordinator replicates by ID."],
        ["3", "Open cluster stats. Explain that per-shard counts are physical copies and can sum to more than logical document count."],
        ["4", "Search for a topic. Show distances and readable text, then point out the query is fanned out and top-K merged."],
        ["5", "Open benchmark. Explain recall, ef_search, exact baseline, and the current sequential-baseline caveat."],
        ["6", "Explain limits honestly: HTTP today; failover, gRPC, active SIMD/SQ8 still future work."],
    ], [.6 * inch, 6.54 * inch])
    p("One-minute script", h2)
    p("“VectorForge is my from-scratch vector database. The browser converts text into a normalized 384-dimensional embedding. A Java coordinator hashes the document ID and writes it to two shard processes. Each shard logs the write before placing it in memory and indexing it with my Java HNSW implementation. At query time, all shards search in parallel and the coordinator merges the nearest results. I compare recall against a Java brute-force oracle. The current cluster uses HTTP between processes; gRPC and automatic failover are planned.”", callout)
    p("Likely questions", h2)
    make_table([
        ["Question", "Answer"],
        ["Why more shard vectors than documents?", "Replication factor 2 stores each logical ID on two physical nodes."],
        ["What is recall?", "Fraction of exact top-K neighbors recovered by HNSW."],
        ["What does ef_search do?", "Sets search candidate breadth; higher typically costs more latency for improved recall."],
        ["Can it survive a crash?", "Acknowledged WAL records are replayed. Automatic node promotion is not implemented."],
        ["Why keep brute force?", "It is the exact correctness/recall baseline, not the production ANN index."],
        ["Is data erased by Clear?", "Active IDs are tombstoned; old serialized bytes may remain in WAL until physical cleanup."],
    ], [2.0 * inch, 5.14 * inch])

    doc.build(story, onFirstPage=draw_page_chrome, onLaterPages=draw_page_chrome)
    print(f"Created {OUTPUT.resolve()}")


def draw_page_chrome(canvas, doc):
    canvas.saveState()
    width, height = letter
    canvas.setFillColor(PAPER)
    canvas.rect(0, height - 10, width, 10, fill=1, stroke=0)
    canvas.setStrokeColor(LINE)
    canvas.line(43, 31, width - 43, 31)
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(MUTED)
    canvas.drawString(44, 19, "VECTORFORGE / MASTER PROJECT EXPLAINER")
    canvas.drawRightString(width - 44, 19, f"OCTOBER 2026  /  PAGE {doc.page}")
    canvas.restoreState()


if __name__ == "__main__":
    build_pdf()
