package com.vulntriage.repository.api;

import com.vulntriage.domain.ScanRun;
import com.vulntriage.domain.enums.ScannerType;

import java.util.List;
import java.util.Optional;

public interface ScanRunRepository {

    /** Persist a new scan run. Sets the generated ID on the object. */
    void save(ScanRun scanRun);

    Optional<ScanRun> findById(long id);

    List<ScanRun> findByRepositoryId(long repositoryId);

    /** Update status and completedAt after a scan finishes. */
    void update(ScanRun scanRun);

    /** Most recent scan run of a given type for a repository. */
    Optional<ScanRun> findLatest(long repositoryId, ScannerType scannerType);
}
