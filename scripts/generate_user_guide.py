from __future__ import annotations
import sys
try:
    from reportlab.lib.pagesizes import letter
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, HRFlowable, Table, TableStyle
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib import colors
    from reportlab.lib.units import inch
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "reportlab"])
    from reportlab.lib.pagesizes import letter
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, HRFlowable, Table, TableStyle
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib import colors
    from reportlab.lib.units import inch

def build_guide():
    doc = SimpleDocTemplate(
        "VectorForge_User_Guide_v2.pdf",
        pagesize=letter,
        rightMargin=52, leftMargin=52,
        topMargin=48, bottomMargin=48
    )

    styles = getSampleStyleSheet()

    # Custom styles
    cover_title = ParagraphStyle("CoverTitle", parent=styles["Title"],
        fontSize=28, textColor=colors.HexColor("#1a237e"), spaceAfter=10, leading=34)
    cover_sub = ParagraphStyle("CoverSub", parent=styles["Normal"],
        fontSize=13, textColor=colors.HexColor("#455a64"), spaceAfter=4, leading=18)
    h1 = ParagraphStyle("H1", parent=styles["Heading1"],
        fontSize=16, textColor=colors.HexColor("#1a237e"), spaceBefore=18, spaceAfter=6,
        borderPad=4, leading=20)
    h2 = ParagraphStyle("H2", parent=styles["Heading2"],
        fontSize=13, textColor=colors.HexColor("#0d47a1"), spaceBefore=12, spaceAfter=4, leading=16)
    body = ParagraphStyle("Body", parent=styles["Normal"],
        fontSize=10.5, leading=16, spaceAfter=4)
    bullet = ParagraphStyle("Bullet", parent=body, leftIndent=18, spaceAfter=3)
    code = ParagraphStyle("Code", parent=body,
        fontName="Courier", fontSize=9.5, backColor=colors.HexColor("#f5f5f5"),
        leftIndent=14, rightIndent=14, leading=14, borderPad=6)
    note = ParagraphStyle("Note", parent=body,
        backColor=colors.HexColor("#e3f2fd"), leftIndent=14, rightIndent=14,
        borderPad=6, textColor=colors.HexColor("#0d47a1"))
    warn = ParagraphStyle("Warn", parent=body,
        backColor=colors.HexColor("#fff3e0"), leftIndent=14, rightIndent=14,
        borderPad=6, textColor=colors.HexColor("#e65100"))
    tip = ParagraphStyle("Tip", parent=body,
        backColor=colors.HexColor("#e8f5e9"), leftIndent=14, rightIndent=14,
        borderPad=6, textColor=colors.HexColor("#1b5e20"))
    tab_hdr = ParagraphStyle("TabHdr", parent=body,
        fontName="Helvetica-Bold", fontSize=10.5,
        textColor=colors.HexColor("#1a237e"))

    def HR():
        return HRFlowable(width="100%", thickness=0.5, color=colors.HexColor("#90caf9"), spaceAfter=6)

    def sp(h=8):
        return Spacer(1, h)

    story = []

    # ── COVER ──────────────────────────────────────────────────────
    story += [
        sp(60),
        Paragraph("🧬 VectorForge", cover_title),
        Paragraph("Complete User Guide", cover_title),
        sp(10),
        HR(),
        Paragraph("A from-scratch distributed vector database with a built-in embedding model,", cover_sub),
        Paragraph("a Streamlit web UI, and a live benchmarking dashboard.", cover_sub),
        sp(6),
        Paragraph("Version 1.0 · September 2026", cover_sub),
        sp(80),
    ]

    # ── TABLE OF CONTENTS ───────────────────────────────────────────
    story.append(Paragraph("Table of Contents", h1))
    story.append(HR())
    toc_data = [
        ["1.", "Prerequisites & Installation"],
        ["2.", "Starting the System (Java Server)"],
        ["3.", "Starting the Web UI"],
        ["4.", "UI Walk-through — Tab by Tab"],
        ["   4.1", "➕  Insert a Single Document"],
        ["   4.2", "📦  Bulk Upload (File / Paste)"],
        ["   4.3", "🔍  Semantic Search"],
        ["   4.4", "📋  Browse & Delete Documents"],
        ["   4.5", "📊  Benchmark on Your Data"],
        ["5.", "Using the Java Client SDK"],
        ["6.", "Using cURL from the Terminal"],
        ["7.", "Understanding the Benchmark Graphs"],
        ["8.", "Tuning HNSW Parameters"],
        ["9.", "Troubleshooting"],
    ]
    for num, title in toc_data:
        story.append(Paragraph(f"<font name='Helvetica-Bold'>{num}</font>  {title}", body))
    story.append(sp(20))

    # ── SECTION 1 ───────────────────────────────────────────────────
    story.append(Paragraph("1.  Prerequisites & Installation", h1))
    story.append(HR())
    story.append(Paragraph("Before running VectorForge, make sure you have the following installed on your machine:", body))
    story.append(sp(4))

    prereqs = [
        ["Requirement", "Minimum Version", "Notes"],
        ["Java JDK", "21+", "Required to compile and run the database engine"],
        ["Python", "3.10+", "Required for the Streamlit web UI"],
        ["IntelliJ IDEA / any IDE", "Any", "Used to run the Java HttpGateway server"],
        ["pip", "Latest", "Used to install Python packages"],
    ]
    t = Table(prereqs, colWidths=[2.0*inch, 1.5*inch, 3.0*inch])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), colors.HexColor("#1a237e")),
        ("TEXTCOLOR",  (0,0), (-1,0), colors.white),
        ("FONTNAME",   (0,0), (-1,0), "Helvetica-Bold"),
        ("FONTSIZE",   (0,0), (-1,-1), 9.5),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [colors.HexColor("#f5f5f5"), colors.white]),
        ("GRID", (0,0), (-1,-1), 0.4, colors.HexColor("#90caf9")),
        ("TOPPADDING",  (0,0), (-1,-1), 5),
        ("BOTTOMPADDING", (0,0), (-1,-1), 5),
        ("LEFTPADDING",   (0,0), (-1,-1), 6),
    ]))
    story.append(t)
    story.append(sp(12))
    story.append(Paragraph("Install all Python dependencies with one command:", body))
    story.append(Paragraph("pip install -r VectorForge/frontend/requirements.txt", code))
    story.append(sp(4))
    story.append(Paragraph("📦  This installs: streamlit, sentence-transformers, requests, numpy, pandas, matplotlib, scikit-learn, hnswlib", note))

    # ── SECTION 2 ───────────────────────────────────────────────────
    story.append(Paragraph("2.  Starting the System — Java Server", h1))
    story.append(HR())
    story.append(Paragraph("VectorForge runs as a lightweight HTTP server on port 8080. You must start this before opening the UI.", body))
    story.append(sp(6))
    story.append(Paragraph("Steps:", h2))
    steps = [
        "Open the project folder <b>VectorForge/</b> in IntelliJ IDEA (or any Java IDE).",
        "Navigate to:  <font name='Courier'>api/src/main/java/com/vectorforge/api/http/HttpGateway.java</font>",
        "Click the green ▶ Run button next to the <font name='Courier'>main()</font> method.",
        "Wait for the console to print:  <b>VectorForge HTTP Gateway started on port 8080</b>",
    ]
    for i, s in enumerate(steps):
        story.append(Paragraph(f"<b>Step {i+1}:</b>  {s}", bullet))
    story.append(sp(6))
    story.append(Paragraph("⚠️  The server stores all vector data under a local  data/  folder next to where you run it. This data persists across restarts because of the Write-Ahead Log (WAL) — all vectors are automatically recovered from disk on startup.", warn))

    # ── SECTION 3 ───────────────────────────────────────────────────
    story.append(Paragraph("3.  Starting the Web UI", h1))
    story.append(HR())
    story.append(Paragraph("Open a terminal window, navigate to the project root, and run:", body))
    story.append(Paragraph("streamlit run VectorForge/frontend/app.py", code))
    story.append(sp(4))
    story.append(Paragraph("Your browser will open automatically at  http://localhost:8501", body))
    story.append(sp(4))
    story.append(Paragraph("✅  The sidebar shows a green  'Java Server: Connected'  badge when the server on port 8080 is reachable. If it is red, go back to Section 2 and start the Java server first.", tip))
    story.append(sp(6))
    story.append(Paragraph("The sidebar also lets you:", body))
    story.append(Paragraph("• Change the VectorForge server URL (useful for remote deployments)", bullet))
    story.append(Paragraph("• Set the Top-K number of results returned by searches", bullet))
    story.append(Paragraph("• See how many documents are currently in your local store", bullet))

    # ── SECTION 4 ───────────────────────────────────────────────────
    story.append(Paragraph("4.  UI Walk-through — Tab by Tab", h1))
    story.append(HR())

    # 4.1
    story.append(Paragraph("4.1  ➕ Insert a Single Document", h2))
    story.append(Paragraph("Use this tab when you want to add one document at a time.", body))
    story.append(sp(4))
    story.append(Paragraph("How to use:", tab_hdr))
    story.append(Paragraph("1.  Type or paste any text into the large text area (a sentence, paragraph, or article).", bullet))
    story.append(Paragraph("2.  Optionally enter a custom ID (e.g. 'product_101'). Leave it blank for an auto-generated UUID.", bullet))
    story.append(Paragraph("3.  Click  🚀 Embed & Insert.", bullet))
    story.append(Paragraph("4.  The app embeds your text locally using the all-MiniLM-L6-v2 model (384 dimensions), sends the vector to the Java server, and shows a success message with the first 8 dimensions of the embedding.", bullet))
    story.append(sp(4))
    story.append(Paragraph("📌  Your original text is also saved into a local SQLite file (local_store.db) alongside the ID so that search results can show real text instead of just UUIDs.", note))

    # 4.2
    story.append(Paragraph("4.2  📦 Bulk Upload", h2))
    story.append(Paragraph("Use this tab when you have many documents to insert at once.", body))
    story.append(sp(4))
    story.append(Paragraph("Supported input modes:", tab_hdr))

    bulk_data = [
        ["Mode", "Format", "How to use"],
        ["Upload .txt file", "One document per line", "Click 'Browse files', select your .txt file"],
        ["Upload .csv file", "Must have a column named 'text'.\nOptional column named 'id'.", "Click 'Browse files', select your .csv file"],
        ["Paste raw text", "One document per line", "Select 'Paste raw text' radio, type or paste into the box"],
    ]
    bt = Table(bulk_data, colWidths=[1.5*inch, 2.2*inch, 2.8*inch])
    bt.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), colors.HexColor("#0d47a1")),
        ("TEXTCOLOR",  (0,0), (-1,0), colors.white),
        ("FONTNAME",   (0,0), (-1,0), "Helvetica-Bold"),
        ("FONTSIZE",   (0,0), (-1,-1), 9),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [colors.HexColor("#f5f5f5"), colors.white]),
        ("GRID", (0,0), (-1,-1), 0.4, colors.HexColor("#90caf9")),
        ("TOPPADDING",  (0,0), (-1,-1), 5),
        ("BOTTOMPADDING", (0,0), (-1,-1), 5),
        ("LEFTPADDING",   (0,0), (-1,-1), 6),
        ("VALIGN", (0,0), (-1,-1), "TOP"),
    ]))
    story.append(bt)
    story.append(sp(8))
    story.append(Paragraph("After loading, a preview of the first 5 documents is shown. Click  🚀 Embed All & Insert  to start. A progress bar tracks every document being embedded and inserted.", body))
    story.append(sp(4))
    story.append(Paragraph("💡  For large datasets (10,000+ lines), this step can take a few minutes because the embedding model runs on your CPU. The progress bar will keep you updated.", tip))

    # 4.3
    story.append(Paragraph("4.3  🔍 Semantic Search", h2))
    story.append(Paragraph("Search your database using natural language — no need to know the exact words in your documents.", body))
    story.append(sp(4))
    story.append(Paragraph("How to use:", tab_hdr))
    story.append(Paragraph("1.  Type a question or phrase in plain English in the search box.", bullet))
    story.append(Paragraph("2.  Click  Search.", bullet))
    story.append(Paragraph("3.  The query gets embedded, sent to the Java HNSW index, and results come back ranked by distance.", bullet))
    story.append(Paragraph("4.  Each result card shows: rank number, distance score (lower = more similar), and the full original text of the matching document.", bullet))
    story.append(sp(4))
    story.append(Paragraph("Example: If you inserted articles about sports and then search for 'fastest athlete in history', VectorForge will return the most semantically related articles even if they don't contain those exact words.", note))

    # 4.4
    story.append(Paragraph("4.4  📋 Browse & Delete Documents", h2))
    story.append(Paragraph("View and manage every document in your local store.", body))
    story.append(sp(4))
    story.append(Paragraph("• Use the filter box to search by keyword across all documents.", bullet))
    story.append(Paragraph("• Click any document row to expand and see the full text.", bullet))
    story.append(Paragraph("• Click  Delete  inside an expanded row to remove a single document.", bullet))
    story.append(Paragraph("• Click  🗑️ Clear All  at the bottom to wipe the entire local store.", bullet))
    story.append(sp(4))
    story.append(Paragraph("⚠️  Deleting from the local store only removes the text record. The vector remains in the Java database until you restart the server. In a production version, you would send a DELETE request to the Java server as well.", warn))

    # 4.5
    story.append(Paragraph("4.5  📊 Benchmark on Your Data", h2))
    story.append(Paragraph("This is the most powerful tab. It runs a rigorous performance evaluation using all documents you have inserted.", body))
    story.append(sp(4))
    story.append(Paragraph("What it measures:", tab_hdr))
    story.append(Paragraph("• Recall@K — What fraction of the true nearest neighbours did HNSW find?  (1.0 = perfect)", bullet))
    story.append(Paragraph("• Average Query Latency (ms) — How fast is each query on average?", bullet))
    story.append(Paragraph("• Speedup — How many times faster is HNSW than Brute Force?", bullet))
    story.append(sp(4))
    story.append(Paragraph("How it works:", tab_hdr))
    story.append(Paragraph("1.  All documents in the local store are embedded into vectors.", bullet))
    story.append(Paragraph("2.  A BruteForce exact KNN (using scikit-learn) is run to establish ground truth.", bullet))
    story.append(Paragraph("3.  An in-memory HNSW index (hnswlib) is built on the same vectors.", bullet))
    story.append(Paragraph("4.  The ef_search parameter is swept across [10, 20, 40, 80, 150, 300].", bullet))
    story.append(Paragraph("5.  For each ef_search value, Recall and Latency are measured.", bullet))
    story.append(Paragraph("6.  Three charts are rendered and a results table is displayed.", bullet))
    story.append(Paragraph("7.  A PNG download button lets you save the chart.", bullet))
    story.append(sp(4))
    story.append(Paragraph("You need at least 5 documents inserted before the Benchmark tab will allow you to run it.", note))

    # ── SECTION 5 ───────────────────────────────────────────────────
    story.append(Paragraph("5.  Using the Java Client SDK", h1))
    story.append(HR())
    story.append(Paragraph("If you are building a Java application that needs to store and search vectors, use the built-in client SDK:", body))
    story.append(Paragraph(
        "VectorForgeClient client = new VectorForgeClient(\"localhost\", 8080);\n\n"
        "// Insert a vector\n"
        "client.upsert(\"product_42\", new float[]{0.1f, 0.5f, -0.3f});\n\n"
        "// Search\n"
        "String results = client.query(new float[]{0.1f, 0.5f, -0.3f});\n"
        "System.out.println(results);",
        code))
    story.append(sp(4))
    story.append(Paragraph("File location:  client-sdk/src/main/java/com/vectorforge/clientsdk/VectorForgeClient.java", body))

    # ── SECTION 6 ───────────────────────────────────────────────────
    story.append(Paragraph("6.  Using cURL from the Terminal", h1))
    story.append(HR())
    story.append(Paragraph("You can also talk to VectorForge directly using cURL:", body))
    story.append(sp(4))
    story.append(Paragraph("Insert a vector:", h2))
    story.append(Paragraph('curl -X POST http://localhost:8080/upsert -d "{\"id\":\"doc1\", \"vector\":[0.1, 0.2, 0.3]}"', code))
    story.append(sp(4))
    story.append(Paragraph("Search for nearest neighbours:", h2))
    story.append(Paragraph('curl -X POST http://localhost:8080/query -d "{\"vector\":[0.1, 0.2, 0.3]}"', code))
    story.append(sp(4))
    story.append(Paragraph("Expected response from query:", h2))
    story.append(Paragraph('[{"id":"doc1", "distance":0.000000}]', code))

    # ── SECTION 7 ───────────────────────────────────────────────────
    story.append(Paragraph("7.  Understanding the Benchmark Graphs", h1))
    story.append(HR())

    graphs_data = [
        ["Chart", "X-Axis", "Y-Axis", "What it tells you"],
        ["Recall vs Latency Curve", "Recall@K (0–1)", "Latency (ms)", "The core accuracy-speed tradeoff. Ideal operating point is top-right (high recall, low latency)."],
        ["Recall Bar Chart", "ef_search value", "Recall@K", "Shows exactly how much accuracy you gain by increasing ef_search."],
        ["Latency Bar Chart", "ef_search value", "Latency (ms)", "Shows the cost in speed for each ef_search setting. Compare against the red Brute Force baseline."],
    ]
    gt = Table(graphs_data, colWidths=[1.5*inch, 1.2*inch, 1.2*inch, 2.6*inch])
    gt.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), colors.HexColor("#1b5e20")),
        ("TEXTCOLOR",  (0,0), (-1,0), colors.white),
        ("FONTNAME",   (0,0), (-1,0), "Helvetica-Bold"),
        ("FONTSIZE",   (0,0), (-1,-1), 9),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [colors.HexColor("#f1f8e9"), colors.white]),
        ("GRID", (0,0), (-1,-1), 0.4, colors.HexColor("#a5d6a7")),
        ("TOPPADDING",  (0,0), (-1,-1), 5),
        ("BOTTOMPADDING", (0,0), (-1,-1), 5),
        ("LEFTPADDING",   (0,0), (-1,-1), 6),
        ("VALIGN", (0,0), (-1,-1), "TOP"),
    ]))
    story.append(gt)
    story.append(sp(10))
    story.append(Paragraph("The red dashed Brute Force line shows the latency of the exact (100% recall) approach. Points on the HNSW curve to the left of this line are cases where HNSW is faster. Points with Recall near 1.0 are cases where HNSW is nearly as accurate as exact search.", body))

    # ── SECTION 8 ───────────────────────────────────────────────────
    story.append(Paragraph("8.  Tuning HNSW Parameters", h1))
    story.append(HR())

    tune_data = [
        ["Parameter", "Default", "Effect of Increasing", "When to increase"],
        ["M", "16", "More edges per node → higher recall, more RAM", "When recall is too low and you have RAM to spare"],
        ["efConstruction", "200", "More neighbours considered during build → better graph quality, slower insert", "When recall at low ef_search is unsatisfactory"],
        ["ef_search", "50", "More nodes visited during query → higher recall, higher latency", "When you need more accuracy and can tolerate slower queries"],
    ]
    tt = Table(tune_data, colWidths=[1.3*inch, 0.8*inch, 2.2*inch, 2.2*inch])
    tt.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), colors.HexColor("#4a148c")),
        ("TEXTCOLOR",  (0,0), (-1,0), colors.white),
        ("FONTNAME",   (0,0), (-1,0), "Helvetica-Bold"),
        ("FONTSIZE",   (0,0), (-1,-1), 9),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [colors.HexColor("#f3e5f5"), colors.white]),
        ("GRID", (0,0), (-1,-1), 0.4, colors.HexColor("#ce93d8")),
        ("TOPPADDING",  (0,0), (-1,-1), 5),
        ("BOTTOMPADDING", (0,0), (-1,-1), 5),
        ("LEFTPADDING",   (0,0), (-1,-1), 6),
        ("VALIGN", (0,0), (-1,-1), "TOP"),
    ]))
    story.append(tt)
    story.append(sp(8))
    story.append(Paragraph("💡  Rule of thumb: For most datasets, M=16, efConstruction=200, ef_search=50 gives >95% recall at 5–10x the speed of brute force.", tip))

    # ── SECTION 9 ───────────────────────────────────────────────────
    story.append(Paragraph("9.  Troubleshooting", h1))
    story.append(HR())

    issues = [
        ("❌ Java Server: Not Running (red badge in sidebar)",
         "Start HttpGateway.main() in your IDE. The Java server must be running before the UI."),
        ("Error connecting to VectorForge at http://localhost:8080",
         "Make sure the Java server is running and your firewall is not blocking port 8080."),
        ("Search returns UUIDs with no text",
         "The document was inserted without going through the UI (e.g. via cURL). The local SQLite store only knows about documents inserted through the frontend."),
        ("Benchmark tab says 'insert at least 5 documents'",
         "Go to the Insert or Bulk Upload tab first and add some data."),
        ("Embedding is very slow on first run",
         "The all-MiniLM-L6-v2 model is being downloaded (~90MB). This only happens once."),
        ("hnswlib install fails on Windows",
         "Run: pip install --pre hnswlib  OR install Visual C++ Build Tools from Microsoft first."),
    ]
    for prob, sol in issues:
        story.append(Paragraph(f"<b>{prob}</b>", warn))
        story.append(Paragraph(f"→  {sol}", bullet))
        story.append(sp(4))

    doc.build(story)
    print("SUCCESS: VectorForge_User_Guide_v2.pdf generated!")

if __name__ == "__main__":
    build_guide()
