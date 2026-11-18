# VectorForge Server Startup Guide

Use **five separate PowerShell terminals** from the VectorForge project root. Start the shards first, then the coordinator, then the browser UI. Do not run the old Streamlit command; the current frontend is React/Vite.

## 1. Compile the Java services

Run once from the project root:

```powershell
$sources = Get-ChildItem api,cluster,shard-node,storage-engine,vector-index,benchmark,client-sdk -Recurse -Filter *.java -File |
  Where-Object { $_.FullName -match '\\src\\main\\java\\' } |
  ForEach-Object { $_.FullName }

New-Item -ItemType Directory -Force .dist\javac-classes | Out-Null
javac --add-modules jdk.httpserver,jdk.incubator.vector -d .dist\javac-classes $sources
```

Wait for compilation to finish. The incubating Vector API warning is expected.

## 2. Start the three Java shard processes

Run each command in its own PowerShell terminal, from the project root. Leave each terminal open.

**Shard 1**

```powershell
java --add-modules jdk.httpserver,jdk.incubator.vector -cp .dist/javac-classes com.vectorforge.shardnode.ShardNodeGateway 9101 data\process-shard-1
```

**Shard 2**

```powershell
java --add-modules jdk.httpserver,jdk.incubator.vector -cp .dist/javac-classes com.vectorforge.shardnode.ShardNodeGateway 9102 data\process-shard-2
```

**Shard 3**

```powershell
java --add-modules jdk.httpserver,jdk.incubator.vector -cp .dist/javac-classes com.vectorforge.shardnode.ShardNodeGateway 9103 data\process-shard-3
```

Wait for each terminal to print `Shard node gateway started on port ...`. The `Recovering ShardNode state from WAL...` message is expected; recovery can take a while with a large WAL.

Each shard uses its own persistent directory under `data\process-shard-*`. Do not delete those folders if you want to keep the saved data.

## 3. Start the coordinator

In a fourth PowerShell terminal, from the project root, run:

```powershell
java '--add-modules' 'jdk.httpserver,jdk.incubator.vector' '-Dvectorforge.remoteShards=http://localhost:9101,http://localhost:9102,http://localhost:9103' -cp .dist/javac-classes com.vectorforge.api.http.HttpGateway
```

Wait for `VectorForge HTTP Gateway started on port 8080`.

## 4. Start the web frontend

In a fifth PowerShell terminal, from the project root, run:

```powershell
npm --prefix .\frontend run dev -- --host 0.0.0.0 --port 5173
```

Open the UI at <http://localhost:5173>.

The embedding model runs in the browser through Transformers.js/WebAssembly. Its model files download on first use and are cached by that browser. Java handles vector storage, indexing, search, and cluster operations.

## 5. Verify the cluster

From another PowerShell terminal:

```powershell
Invoke-RestMethod http://localhost:8080/v1/cluster/nodes | ConvertTo-Json -Depth 5
```

Expect `node_count` to be `3` and all shard statuses to say `healthy`. Check each shard directly if needed:

```powershell
Invoke-RestMethod http://localhost:9101/stats
Invoke-RestMethod http://localhost:9102/stats
Invoke-RestMethod http://localhost:9103/stats
```

Verify the frontend and its API proxy respond:

```powershell
(Invoke-WebRequest http://localhost:5173/ -UseBasicParsing).StatusCode
(Invoke-WebRequest http://localhost:5173/api/v1/cluster/nodes -UseBasicParsing).StatusCode
```

Both should return `200`.

## Shutdown

Press **Ctrl+C** in each of the five service terminals. The shard WALs and data directories remain on disk for the next startup.

## Common problems

- **Port already in use:** check ports `5173`, `8080`, `9101`, `9102`, and `9103`. Stop the old VectorForge service terminal before starting a duplicate.
- **Coordinator reports unhealthy shards:** start all three shard processes first and wait for WAL recovery to finish, then start the coordinator.
- **Java cannot find the main class:** compile from the project root and launch with the same `.dist/javac-classes` directory shown above.
- **Frontend shows offline:** confirm the coordinator answers at `http://localhost:8080/v1/cluster/nodes`, then refresh `http://localhost:5173`.
