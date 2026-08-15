package com.vulntriage.repository.api;

import com.vulntriage.domain.PromptTemplate;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository {

    void save(PromptTemplate template);

    void update(PromptTemplate template);

    void deleteById(long id);

    Optional<PromptTemplate> findById(long id);

    Optional<PromptTemplate> findByVersion(String version);

    List<PromptTemplate> findAll();
}
