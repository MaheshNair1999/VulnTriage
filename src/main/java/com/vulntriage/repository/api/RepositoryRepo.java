package com.vulntriage.repository.api;

import com.vulntriage.domain.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Repository (source code repo) persistence.
 * Named RepositoryRepo to avoid confusion with the pattern name itself.
 */
public interface RepositoryRepo {

    void save(Repository repository);

    Optional<Repository> findById(long id);

    List<Repository> findAll();

    void update(Repository repository);

    void delete(long id);

    long countAll();
}
