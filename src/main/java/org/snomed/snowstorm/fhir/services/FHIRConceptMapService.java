package org.snomed.snowstorm.fhir.services;


import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import com.google.common.collect.Lists;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.snomed.snowstorm.core.data.domain.CodeSystem;
import org.snomed.snowstorm.core.data.domain.CodeSystemVersion;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.ReferenceSetMember;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.ConceptService;
import org.snomed.snowstorm.core.data.services.ReferenceSetMemberService;
import org.snomed.snowstorm.core.data.services.pojo.MemberSearchRequest;
import org.snomed.snowstorm.core.pojo.LanguageDialect;
import org.snomed.snowstorm.fhir.config.FHIRConceptMapImplicitConfig;
import org.snomed.snowstorm.fhir.domain.*;
import org.snomed.snowstorm.fhir.pojo.FHIRCodeSystemVersionParams;
import org.snomed.snowstorm.fhir.pojo.FHIRSnomedConceptMapConfig;
import org.snomed.snowstorm.fhir.repositories.FHIRConceptMapRepository;
import org.snomed.snowstorm.fhir.repositories.FHIRMapElementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Comparator.*;
import static co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.*;
import static io.kaicode.elasticvc.helper.QueryHelper.*;
import static org.snomed.snowstorm.core.util.CollectionUtils.orEmpty;
import static org.snomed.snowstorm.fhir.config.FHIRConstants.SNOMED_URI;
import static org.snomed.snowstorm.fhir.services.FHIRHelper.exception;

@Service
public class FHIRConceptMapService {

	public static final String WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX = "?fhir_vs";

	private static final PageRequest PAGE_OF_ONE_THOUSAND = PageRequest.of(0, 1_000);

	private final FHIRConceptMapRepository conceptMapRepository;

	private final ElasticsearchOperations elasticsearchOperations;

	private final FHIRMapElementRepository mapElementRepository;

	private final FHIRCodeSystemService fhirCodeSystemService;

	private final CodeSystemService codeSystemService;

	private final ReferenceSetMemberService snomedRefsetMemberService;

	private final ConceptService snomedConceptService;

	private final FHIRConceptMapImplicitConfig implicitMapConfig;

	private final FHIRConceptService conceptService;

	private final FHIRSnomedModelTermCache snomedModelTermCache;

	// Implicit ConceptMaps - format http://snomed.info/sct[/(module)[/version/(version)]]?fhir_cm=(sctid)
	private List<FHIRSnomedConceptMapConfig> snomedMaps;

	// Map of SNOMED CT map correlation concepts to FHIR equivalence codes - http://hl7.org/fhir/concept-map-equivalence
	private Map<String, Enumerations.ConceptMapEquivalence> snomedCorrelationToFhirEquivalenceMap;

	public FHIRConceptMapService(FHIRConceptMapRepository conceptMapRepository, ElasticsearchOperations elasticsearchOperations, FHIRMapElementRepository mapElementRepository, FHIRCodeSystemService fhirCodeSystemService, CodeSystemService codeSystemService, ReferenceSetMemberService snomedRefsetMemberService, ConceptService snomedConceptService, FHIRConceptMapImplicitConfig implicitMapConfig, FHIRConceptService conceptService, FHIRSnomedModelTermCache snomedModelTermCache) {
		this.conceptMapRepository = conceptMapRepository;
		this.elasticsearchOperations = elasticsearchOperations;
		this.mapElementRepository = mapElementRepository;
		this.fhirCodeSystemService = fhirCodeSystemService;
		this.codeSystemService = codeSystemService;
		this.snomedRefsetMemberService = snomedRefsetMemberService;
		this.snomedConceptService = snomedConceptService;
		this.implicitMapConfig = implicitMapConfig;
		this.conceptService = conceptService;
		this.snomedModelTermCache = snomedModelTermCache;
	}

	@PostConstruct
	public void init() {
		snomedMaps = implicitMapConfig.getImplicitMaps();
		snomedCorrelationToFhirEquivalenceMap = implicitMapConfig.getSnomedCorrelationToFhirEquivalenceMap();
	}

	/**
	 * Map elements are written and deleted this many at a time. A group's elements used to go to
	 * Elasticsearch in one bulk request, and a large map is larger than the request body Elasticsearch
	 * accepts (http.max_content_length, 100 MB by default): Health Canada's licence-to-ingredient map
	 * is one group of 153,003 elements and about 110 MB as FHIR JSON, and was refused with HTTP 413.
	 * Its elements average 720 bytes and reach 55 KB; the largest 5,000 consecutive ones come to about
	 * 6 MB, so a batch of 5,000 stays an order of magnitude under the limit on that map.
	 */
	private static final int MAP_ELEMENT_BATCH_SIZE = 5_000;

	public FHIRConceptMap createOrUpdate(FHIRConceptMap conceptMap) {
		// FHIR ConceptMap canonical is `url|version` and both are required for persistence.
		String url = conceptMap.getUrl();
		if (url == null || url.isBlank()) {
			throw exception("ConceptMap 'url' is required (canonical is `url|version`).", OperationOutcome.IssueType.INVARIANT, 400);
		}

		String version = conceptMap.getVersion();
		if (version == null || version.isBlank()) {
			throw exception("ConceptMap 'version' is required (canonical is `url|version`).", OperationOutcome.IssueType.INVARIANT, 400);
		}

		if (url.contains("?fhir_cm")) {
			throw exception("ConceptMap url must not contain 'fhir_cm', this is reserved for implicit concept maps.", OperationOutcome.IssueType.INVARIANT, 400);
		}

		// The copies this one replaces: those at the same url and version, and whatever is stored under its
		// id, in either form (see findStoredById), so that a map stored under "ConceptMap/<id>" and PUT
		// again is replaced rather than left beside the new copy.
		Map<String, FHIRConceptMap> previousById = new LinkedHashMap<>();
		conceptMapRepository.findAllByUrl(url).stream()
				.filter(map -> version.equals(map.getVersion()))
				.forEach(map -> previousById.put(map.getId(), map));
		if (conceptMap.getId() != null) {
			findStoredById(conceptMap.getId()).forEach(map -> previousById.put(map.getId(), map));
		}
		Collection<FHIRConceptMap> previous = previousById.values();

		// The new elements are written before anything else changes, under the new map's own group ids,
		// which nothing reads until its header is saved. If the write fails the previous map is still
		// whole and still answering; the new elements are removed and the failure reported.
		List<String> newGroupIds = orEmpty(conceptMap.getGroup()).stream().map(FHIRConceptMapGroup::getGroupId).toList();
		FHIRConceptMap saved;
		try {
			for (FHIRConceptMapGroup mapGroup : orEmpty(conceptMap.getGroup())) {
				saveElementsInBatches(orEmpty(mapGroup.getElement()));
			}
			saved = conceptMapRepository.save(conceptMap);
		} catch (RuntimeException e) {
			try {
				deleteElementsOfGroups(newGroupIds);
			} catch (RuntimeException cleanup) {
				e.addSuppressed(cleanup);
			}
			throw e;
		}

		// Only then retire the copies this one replaces: the header first, so that a failure part way
		// leaves elements nothing refers to, never a map whose elements have gone. A copy stored under the
		// same id has already been overwritten by the save. Its elements are found by group id: a stored
		// header does not carry them.
		for (FHIRConceptMap old : previous) {
			if (!old.getId().equals(saved.getId())) {
				conceptMapRepository.delete(old);
			}
			deleteElementsOfGroups(orEmpty(old.getGroup()).stream().map(FHIRConceptMapGroup::getGroupId)
					.filter(groupId -> !newGroupIds.contains(groupId)).toList());
		}
		return saved;
	}

	/**
	 * The stored maps a delete addresses: the one stored under this id, or every copy stored at this
	 * url and version. An id names exactly one stored resource, whatever its version.
	 */
	public List<FHIRConceptMap> findStored(String id, String url, String version) {
		if (id != null) {
			return findStoredById(id).stream().limit(1).toList();
		}
		return conceptMapRepository.findAllByUrl(url).stream().filter(map -> version.equals(map.getVersion())).toList();
	}

	/**
	 * What is stored under the id a client uses, "abc": the map stored under "abc", then one stored
	 * under "ConceptMap/abc". Maps stored through PUT used to be kept under the id HAPI hands over,
	 * which carries the type, while clients see them listed, and address them, as "abc". Every read
	 * by id resolves through here, so those maps answer by the id they are listed under.
	 */
	private List<FHIRConceptMap> findStoredById(String id) {
		List<FHIRConceptMap> stored = new ArrayList<>(2);
		conceptMapRepository.findById(id).ifPresent(stored::add);
		conceptMapRepository.findById("ConceptMap/" + id).ifPresent(stored::add);
		return stored;
	}

	/** Delete a stored map: its header first, so a failure part way never leaves a map without its elements. */
	public void delete(FHIRConceptMap map) {
		conceptMapRepository.delete(map);
		deleteElementsOfGroups(orEmpty(map.getGroup()).stream().map(FHIRConceptMapGroup::getGroupId).toList());
	}

	private void saveElementsInBatches(List<FHIRMapElement> elements) {
		for (List<FHIRMapElement> batch : Lists.partition(elements, MAP_ELEMENT_BATCH_SIZE)) {
			mapElementRepository.saveAll(batch);
		}
	}

	private void deleteElementsOfGroups(Collection<String> groupIds) {
		if (groupIds.isEmpty()) {
			return;
		}
		Page<FHIRMapElement> page = mapElementRepository.findByGroupIdIn(groupIds, PageRequest.of(0, MAP_ELEMENT_BATCH_SIZE));
		while (!page.isEmpty()) {
			mapElementRepository.deleteAll(page.getContent());
			page = mapElementRepository.findByGroupIdIn(groupIds, PageRequest.of(0, MAP_ELEMENT_BATCH_SIZE));
		}
	}

	/** The map stored under this id, with its elements, and carrying the id as given, however it is stored. */
	public FHIRConceptMap findByIdWithGroups(String idPart) {
		List<FHIRConceptMap> stored = findStoredById(idPart);
		if (stored.isEmpty()) {
			return null;
		}
		FHIRConceptMap map = stored.getFirst();
		map.setId(idPart);
		for (FHIRConceptMapGroup group : orEmpty(map.getGroup())) {
			List<FHIRMapElement> elements = mapElementRepository.findAllByGroupId(group.getGroupId());
			group.setElement(elements);
		}
		return map;
	}

	public List<FHIRConceptMap> findAll() {
		// Load first 1000 until we can figure out pagination
		List<FHIRConceptMap> maps = new ArrayList<>(hasAnyImportedSnomedVersion() ? getSnomedMaps() : List.of());
		PageRequest pageRequest = PageRequest.of(0, PAGE_OF_ONE_THOUSAND.getPageSize() - maps.size());
		maps.addAll(conceptMapRepository.findAll(pageRequest).getContent());
		return maps;
	}

	private boolean hasAnyImportedSnomedVersion() {
		for (CodeSystem edition : codeSystemService.findAll()) {
			CodeSystemVersion version = codeSystemService.findLatestImportedVersion(edition.getShortName());
			if (version != null && !CodeSystemService.isEmpty2000Version(version)) {
				return true;
			}
		}
		return false;
	}

	private List<FHIRConceptMap> getSnomedMaps() {
		List<FHIRConceptMap> generatedMaps = new ArrayList<>();
		for (FHIRSnomedConceptMapConfig snomedMap : snomedMaps) {
			String refsetId = snomedMap.getReferenceSetId();

			FHIRConceptMap map = new FHIRConceptMap();
			map.setId("snomed_implicit_map_" + refsetId);
			map.setUrl("http://snomed.info/sct?fhir_cm=" + refsetId);
			map.setName(snomedMap.getName());
			map.setSourceUri(snomedMap.getSourceSystem() + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX);
			map.setTargetUri(snomedMap.getTargetSystem() + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX);

			// For internal use
			map.setImplicitSnomedMap(true);
			map.setSnomedRefsetId(refsetId);
			map.setSnomedRefsetEquivalence(snomedMap.getRefsetEquivalence());

			generatedMaps.add(map);
		}
		return generatedMaps;
	}

	Collection<FHIRConceptMap> findMaps(String url, Coding coding, String targetSystem, String sourceValueSet, String targetValueSet) {
		BoolQuery.Builder query = bool();
		List<Predicate<FHIRConceptMap>> snomedPredicates = new ArrayList<>();
		if (url != null) {
			if (FHIRHelper.isSnomedUri(url) && url.contains("?")) {
				url = SNOMED_URI + url.substring(url.indexOf("?"));
			}
			query.must(termQuery(FHIRConceptMap.Fields.URL, url));
			String finalUrl = url;
			snomedPredicates.add(map -> finalUrl.equals(map.getUrl()));
		}
		if (coding != null) {
			query.must(termQuery(FHIRConceptMap.Fields.GROUP_SOURCE, coding.getSystem()));
			snomedPredicates.add(map -> (map.getSourceUri() == null || map.getSourceUri().startsWith(coding.getSystem().replace("/xsct", "/sct"))));
		}
		if (targetSystem != null) {
			query.must(termQuery(FHIRConceptMap.Fields.GROUP_TARGET, targetSystem));
			snomedPredicates.add(map -> map.getTargetUri().equals(targetSystem + WHOLE_SYSTEM_VALUE_SET_URI_POSTFIX));
		}
		if (sourceValueSet != null) {
			query.must(bool(b -> b
					// Map either has no source (value set) or it matches the param
					.should(bool(bq -> bq.mustNot(existsQuery(FHIRConceptMap.Fields.SOURCE))))
					.should(termQuery(FHIRConceptMap.Fields.SOURCE, sourceValueSet))
			));
			snomedPredicates.add(map -> map.getSourceUri().equals(sourceValueSet));
		}
		if (targetValueSet != null) {
			query.must(bool(b -> b
					// Map either has no target (value set) or it matches the param
					.should(bool(bq -> bq.mustNot(existsQuery(FHIRConceptMap.Fields.TARGET))))
					.should(termQuery(FHIRConceptMap.Fields.TARGET, targetValueSet))
			));
			snomedPredicates.add(map -> map.getTargetUri().equals(targetValueSet));
		}
		NativeQueryBuilder queryBuilder = new NativeQueryBuilder()
				.withQuery(query.build()._toQuery())
				.withPageable(PageRequest.of(0, 100));

		// Grab maps from store
		List<FHIRConceptMap> maps = new ArrayList<>(searchForList(queryBuilder, FHIRConceptMap.class));

		// Grab generated snomed maps when a SNOMED CT release is loaded
		if (hasAnyImportedSnomedVersion()) {
			maps.addAll(getSnomedMaps().stream()
					.filter(map -> snomedPredicates.stream().allMatch(predicate -> predicate.test(map))).toList());
		}

		return maps;
	}

	public Collection<FHIRMapElement> findMapElements(FHIRConceptMap map, Coding coding, String targetSystem, List<LanguageDialect> languageDialects) {
		if (map.isImplicitSnomedMap()) {
			return generateImplicitSnomedMapElements(map, coding, targetSystem, languageDialects);
		}

		List<FHIRConceptMapGroup> groups = map.getGroup().stream()
				.filter(group -> group.getSource().equals(coding.getSystem()))
				.filter(group -> targetSystem == null || group.getTarget().equals(targetSystem))
				.toList();
		BoolQuery.Builder query = bool()
				.must(termsQuery(FHIRMapElement.Fields.GROUP_ID, groups.stream().map(FHIRConceptMapGroup::getGroupId).toList()))
				.must(termQuery(FHIRMapElement.Fields.CODE, coding.getCode()));
		NativeQueryBuilder queryBuilder = new NativeQueryBuilder()
				.withQuery(query.build()._toQuery())
				.withPageable(PAGE_OF_ONE_THOUSAND);
		return searchForList(queryBuilder, FHIRMapElement.class);
	}

	private Collection<FHIRMapElement> generateImplicitSnomedMapElements(FHIRConceptMap map, Coding coding, String targetSystem, List<LanguageDialect> languageDialects) {
		FHIRCodeSystemVersionParams versionParams = FHIRHelper.getCodeSystemVersionParams((IdType) null, null, null, coding);
		FHIRCodeSystemVersion snomedVersion = fhirCodeSystemService.findCodeSystemVersionOrThrow(versionParams);

		map.setUrl(map.getUrl().replace(SNOMED_URI + "?", snomedVersion.getVersion() + "?"));

		MemberSearchRequest memberSearchRequest = new MemberSearchRequest()
				.referenceSet(map.getSnomedRefsetId())
				.active(true);
		boolean hasSnomedSource = FHIRHelper.isSnomedUri(map.getSourceUri());
		boolean hasSnomedTarget = FHIRHelper.isSnomedUri(map.getTargetUri());
		if (!hasSnomedSource) {
			memberSearchRequest.additionalField(ReferenceSetMember.AssociationFields.MAP_TARGET, coding.getCode());
		} else {
			memberSearchRequest.referencedComponentId(coding.getCode());
		}
		Page<ReferenceSetMember> members = snomedRefsetMemberService.findMembers(snomedVersion.getSnomedBranch(), memberSearchRequest, PAGE_OF_ONE_THOUSAND);

		// Collect map targets for filling terms
		Map<String, List<FHIRMapTarget>> mapTargetsByCode = new HashMap<>();

		Comparator<ReferenceSetMember> mapComparator =
				comparing(ReferenceSetMember::getMapGroup, Comparator.nullsFirst(naturalOrder()))
						.thenComparing(ReferenceSetMember::getMapPriority, Comparator.nullsFirst(naturalOrder()));

		List<FHIRMapElement> generatedElements = members.stream()
				.sorted(mapComparator)
				.map(referenceSetMember -> buildImplicitSnomedMapElement(referenceSetMember, map, coding,
						hasSnomedSource, hasSnomedTarget, snomedVersion, languageDialects, mapTargetsByCode))
				.filter(Objects::nonNull)
				.filter(element -> element.getTarget().get(0).getCode() != null)
				.toList();

		// Grab target display terms
		fillMapTargetDisplayTerms(mapTargetsByCode, hasSnomedTarget, targetSystem, snomedVersion, languageDialects);

		return generatedElements;
	}

	private FHIRMapElement buildImplicitSnomedMapElement(ReferenceSetMember referenceSetMember, FHIRConceptMap map, Coding coding,
			boolean hasSnomedSource, boolean hasSnomedTarget, FHIRCodeSystemVersion snomedVersion,
			List<LanguageDialect> languageDialects, Map<String, List<FHIRMapTarget>> mapTargetsByCode) {
		String targetCode = getTargetCode(hasSnomedSource, hasSnomedTarget, referenceSetMember);
		if (targetCode == null) return null;
		String equivalence = map.getSnomedRefsetEquivalence();
		FHIRMapTarget mapTarget = new FHIRMapTarget(targetCode, equivalence, null);
		mapTargetsByCode.computeIfAbsent(targetCode, key -> new ArrayList<>()).add(mapTarget);
		String message = null;
		String mapGroup = referenceSetMember.getAdditionalField("mapGroup");
		if (mapGroup != null) {
			String mapPriority = referenceSetMember.getAdditionalField("mapPriority");
			String mapRule = referenceSetMember.getAdditionalField("mapRule");
			String mapAdvice = referenceSetMember.getAdditionalField("mapAdvice");
			String correlationId = referenceSetMember.getAdditionalField("correlationId");
			Enumerations.ConceptMapEquivalence mapEquivalence = snomedCorrelationToFhirEquivalenceMap.get(correlationId);
			mapTarget.setEquivalence(mapEquivalence != null ? mapEquivalence.toCode() : null);
			String mapCategoryId = referenceSetMember.getAdditionalField("mapCategoryId");
			String mapCategoryMessage = "";

			// mapCategoryId null for complex map, only used in extended map
			if (mapCategoryId != null) {
				String mapCategoryTerm = snomedModelTermCache.getSnomedTerm(mapCategoryId, snomedVersion, languageDialects);
				mapCategoryMessage = format(", Map Category:'%s'", mapCategoryTerm);
			}

			message = format("Please observe the following map advice. Group:%s, Priority:%s, Rule:%s, Advice:'%s'%s.",
					mapGroup, mapPriority, mapRule, mapAdvice, mapCategoryMessage);
		}
		return new FHIRMapElement()
				.setCode(coding.getCode())
				.setTarget(Collections.singletonList(mapTarget))
				.setMessage(message);
	}

	private void fillMapTargetDisplayTerms(Map<String, List<FHIRMapTarget>> mapTargetsByCode, boolean hasSnomedTarget,
			String targetSystem, FHIRCodeSystemVersion snomedVersion, List<LanguageDialect> languageDialects) {
		if (mapTargetsByCode.isEmpty()) {
			return;
		}
		if (hasSnomedTarget) {
			Map<String, ConceptMini> conceptMiniMap = snomedConceptService.findConceptMinis(snomedVersion.getSnomedBranch(), mapTargetsByCode.keySet(), languageDialects)
					.getResultsMap();
			for (Map.Entry<String, ConceptMini> entry : conceptMiniMap.entrySet()) {
				mapTargetsByCode.get(entry.getKey()).forEach(mapTarget -> mapTarget.setDisplay(entry.getValue().getPt().getTerm()));
			}
		} else {
			Map<String, String> codeDisplayTerms = getCodeDisplayTerms(mapTargetsByCode.keySet(), targetSystem);
			for (Map.Entry<String, String> entry : codeDisplayTerms.entrySet()) {
				mapTargetsByCode.get(entry.getKey()).forEach(mapTarget -> mapTarget.setDisplay(entry.getValue()));
			}
		}
	}

	public Set<FHIRSnomedConceptMapConfig> getConfiguredMapsWithNonSnomedTarget(Set<String> refsetIds) {
		return snomedMaps.stream()
				.filter(map -> refsetIds.contains(map.getReferenceSetId()))
				.filter(map -> !FHIRHelper.isSnomedUri(map.getTargetSystem()))
				.collect(Collectors.toSet());
	}

	@NotNull
	public Map<String, String> getCodeDisplayTerms(Set<String> codes, String systemUrl) {
		if (codes == null || codes.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> codeDisplayTerms = new HashMap<>();
		FHIRCodeSystemVersion targetCodeSystemLatestVersion = fhirCodeSystemService.findCodeSystemVersion(new FHIRCodeSystemVersionParams(systemUrl));
		if (targetCodeSystemLatestVersion != null) {
			Page<FHIRConcept> targetConcepts = conceptService.findConcepts(codes, targetCodeSystemLatestVersion, PageRequest.of(0, 1_000));
			for (FHIRConcept targetConcept : targetConcepts.getContent()) {
				codeDisplayTerms.put(targetConcept.getCode(), targetConcept.getDisplay());
			}
		}
		return codeDisplayTerms;
	}

	private String getTargetCode(boolean hasSnomedSource, boolean hasSnomedTarget, ReferenceSetMember referenceSetMember) {
		String targetCode;
		if (hasSnomedTarget) {
			if (hasSnomedSource) {
				// Association refsets use targetComponentId
				targetCode = referenceSetMember.getAdditionalField(ReferenceSetMember.AssociationFields.TARGET_COMP_ID);
			} else {
				targetCode = referenceSetMember.getReferencedComponentId();
			}
		} else {
			// Target is non-snomed code system
			targetCode = referenceSetMember.getAdditionalField(ReferenceSetMember.AssociationFields.MAP_TARGET);
			if (targetCode == null) {
				// Attribute value refsets use valueId
				targetCode = referenceSetMember.getAdditionalField(ReferenceSetMember.AssociationFields.VALUE_ID);
			}
		}
		return targetCode;
	}

	@NotNull
	private <T> List<T> searchForList(NativeQueryBuilder queryBuilder, Class<T> clazz) {
		return elasticsearchOperations.search(queryBuilder.build(), clazz).stream()
				.map(SearchHit::getContent).toList();
	}
}
