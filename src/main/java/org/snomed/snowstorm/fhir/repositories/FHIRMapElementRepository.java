package org.snomed.snowstorm.fhir.repositories;

import org.snomed.snowstorm.fhir.domain.FHIRMapElement;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

public interface FHIRMapElementRepository extends ElasticsearchRepository<FHIRMapElement, String> {

	Page<FHIRMapElement> findByGroupIdIn(Collection<String> groupIds, Pageable pageable);

}
