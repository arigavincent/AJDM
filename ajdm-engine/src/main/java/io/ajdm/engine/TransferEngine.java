package io.ajdm.engine;

import io.ajdm.model.TransferRequest;
import io.ajdm.model.DownloadRecord;
import io.ajdm.model.ConflictPolicy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TransferEngine extends AutoCloseable {
    TransferHandle start(TransferRequest request) throws IOException;
    TransferHandle start(TransferRequest request, SourceRefresher refresher) throws IOException;
    TransferHandle start(TransferRequest request, SourceRefresher refresher, SourceRefreshPolicy policy) throws IOException;
    TransferHandle resume(UUID id) throws IOException;
    TransferHandle resume(UUID id, ConflictPolicy policy) throws IOException;
    TransferHandle resume(UUID id, SourceRefresher refresher) throws IOException;
    TransferHandle resume(UUID id, Map<String, String> headers, SourceRefresher refresher) throws IOException;
    TransferHandle resume(UUID id, Map<String, String> headers, SourceRefresher refresher, SourceRefreshPolicy policy) throws IOException;
    TransferHandle resume(UUID id, Map<String, String> headers) throws IOException;
    /** Start a new download after a source-change pause, preserving the old record and partial. */
    TransferHandle restart(UUID id) throws IOException;
    TransferHandle restart(UUID id, Map<String, String> headers) throws IOException;
    List<DownloadRecord> downloads() throws IOException;
    @Override void close();
}
