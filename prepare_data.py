import csv
import os

try:
    from sentence_transformers import SentenceTransformer
except ImportError:
    import subprocess
    import sys
    subprocess.check_call([sys.executable, "-m", "pip", "install", "sentence-transformers"])
    from sentence_transformers import SentenceTransformer

def prepare_custom_data():
    if not os.path.exists('my_dataset.txt'):
        print("Error: my_dataset.txt not found. Please create it and add your sentences!")
        return

    print("1. Loading Embedding Model... (all-MiniLM-L6-v2)")
    model = SentenceTransformer('all-MiniLM-L6-v2')

    print("2. Reading your custom data from my_dataset.txt...")
    with open('my_dataset.txt', 'r', encoding='utf-8') as f:
        # Ignore empty lines
        lines = [line.strip() for line in f if line.strip()]

    if not lines:
        print("Error: my_dataset.txt is empty! Please add some text.")
        return

    print(f"3. Embedding {len(lines)} sentences... This might take a moment.")
    embeddings = model.encode(lines)

    print("4. Saving mathematical vectors to custom_vectors.csv...")
    with open('custom_vectors.csv', 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        for i, (text, vec) in enumerate(zip(lines, embeddings)):
            # Row format: ID, dim1, dim2, dim3...
            row = [f"doc_{i}"] + vec.tolist()
            writer.writerow(row)
            
    print(f"\n✅ Success! Converted {len(lines)} texts into a {len(embeddings[0])}-dimensional vector dataset.")
    print("Next step: Run the Java IndexBenchmark. It will automatically detect 'custom_vectors.csv' and benchmark your data!")

if __name__ == "__main__":
    prepare_custom_data()
