package org.snomed.snowstorm.fhir.repositories;

import org.snomed.snowstorm.fhir.domain.FHIRMapElement;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

public interface FHIRMapElementRepository extends ElasticsearchRepository<FHIRMapElement, String> {

	List<FHIRMapElement> findAllByGroupId(String groupId);

	Page<FHIRMapElement> findByGroupIdIn(Collection<String> groupIds, Pageable pageable);

}
