import sys
import subprocess
import os

try:
    import pandas as pd
    import matplotlib.pyplot as plt
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pandas", "matplotlib"])
    import pandas as pd
    import matplotlib.pyplot as plt

def plot_benchmark():
    if not os.path.exists("benchmark_results.csv"):
        print("Error: benchmark_results.csv not found. Did you run the Java benchmark first?")
        return

    df = pd.read_csv("benchmark_results.csv")
    
    bf_row = df[df['Algorithm'] == 'BruteForce'].iloc[0]
    hnsw_df = df[df['Algorithm'] == 'HNSW'].sort_values('Recall')

    plt.figure(figsize=(10, 6))
    
    # Plot HNSW Curve
    plt.plot(hnsw_df['Recall'], hnsw_df['Latency_ms'], marker='o', linestyle='-', linewidth=2, color='royalblue', label='HNSW (M=16)')
    
    # Annotate ef_search values on the points
    for i, row in hnsw_df.iterrows():
        plt.annotate(f"ef={int(row['ef_search'])}", 
                     (row['Recall'], row['Latency_ms']), 
                     textcoords="offset points", 
                     xytext=(0,10), 
                     ha='center', 
                     fontsize=9,
                     fontweight='bold',
                     color='darkblue')
    
    # Plot Brute Force Reference Line
    plt.axhline(y=bf_row['Latency_ms'], color='crimson', linestyle='--', linewidth=2, label='Brute Force (Exact)')
    
    plt.xlabel('Recall@10 (Accuracy)', fontsize=12, fontweight='bold')
    plt.ylabel('Query Latency (milliseconds)', fontsize=12, fontweight='bold')
    plt.title('VectorForge Performance: Recall vs. Latency\n(10,000 Vectors, 128 Dimensions)', fontsize=14, fontweight='bold')
    
    # Format axes
    plt.xlim(max(0.5, hnsw_df['Recall'].min() - 0.05), 1.02)
    plt.grid(True, linestyle='--', alpha=0.7)
    plt.legend(fontsize=11)
    
    # Save the chart
    output_file = 'hnsw_benchmark_chart.png'
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"✅ Successfully generated and saved benchmark graph to: {output_file}")

if __name__ == "__main__":
    plot_benchmark()
