package org.snomed.snowstorm.fhir.services;

import org.snomed.snowstorm.fhir.domain.ConjunctionConstraints;
import org.snomed.snowstorm.fhir.domain.ConceptConstraint;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.util.CollectionUtils;

import java.util.*;

public class CodeSelectionCriteria {

	private final String valueSetUserRef;
	// One ConjunctionConstraints per compose clause. Clauses over one code system version are
	// separate: a code is included when ANY include clause selects it, and excluded when ANY
	// exclude clause selects it. Only the filters WITHIN one clause are ANDed.
	private final Map<FHIRCodeSystemVersion, List<ConjunctionConstraints>> inclusionClauses;
	private final Set<CodeSelectionCriteria> nestedSelections;
	private final Map<FHIRCodeSystemVersion, List<ConjunctionConstraints>> exclusionClauses;

	public CodeSelectionCriteria(String valueSetUserRef) {
		this.valueSetUserRef = valueSetUserRef;
		inclusionClauses = new HashMap<>();
		nestedSelections = new HashSet<>();
		exclusionClauses = new HashMap<>();
	}

	public boolean isOnlyInclusionsForOneVersionAndAllSimple() {
		return CollectionUtils.isEmpty(nestedSelections) && CollectionUtils.isEmpty(exclusionClauses) && !CollectionUtils.isEmpty(inclusionClauses)
				&& inclusionClauses.keySet().size() == 1 && getInclusionConstraints().values().stream().flatMap(conjunctionConstraints -> conjunctionConstraints.constraintsFlattened().stream()).allMatch(ConceptConstraint::isSimpleCodeSet);
	}

	/** Starts a new include clause over this version and returns it. */
	public ConjunctionConstraints addInclusion(FHIRCodeSystemVersion codeSystemVersion) {
		ConjunctionConstraints clause = new ConjunctionConstraints();
		inclusionClauses.computeIfAbsent(codeSystemVersion, v -> new ArrayList<>()).add(clause);
		return clause;
	}

	public void addNested(CodeSelectionCriteria nestedCriteria) {
		nestedSelections.add(nestedCriteria);
	}

	/** Starts a new exclude clause over this version and returns it. */
	public ConjunctionConstraints addExclusion(FHIRCodeSystemVersion codeSystemVersion) {
		ConjunctionConstraints clause = new ConjunctionConstraints();
		exclusionClauses.computeIfAbsent(codeSystemVersion, v -> new ArrayList<>()).add(clause);
		return clause;
	}

	public Set<FHIRCodeSystemVersion> gatherAllInclusionVersions() {
		return doGatherAllInclusionVersions(new HashSet<>());
	}

	public boolean isAnyECL() {
		return getInclusionConstraints().values().stream()
				.flatMap(conjunctionConstraints -> conjunctionConstraints.constraintsFlattened().stream()).anyMatch(ConceptConstraint::hasEcl) ||
				getExclusionConstraints().values().stream().flatMap(conjunctionConstraints -> conjunctionConstraints.constraintsFlattened().stream()).anyMatch(ConceptConstraint::hasEcl) ||
				nestedSelections.stream().anyMatch(CodeSelectionCriteria::isAnyECL);
	}

	public String getValueSetUserRef() {
		return valueSetUserRef;
	}

	/**
	 * Every include clause's constraints per version, merged into one ConjunctionConstraints. The
	 * merge ANDs clauses together, so it is only correct for callers that flatten the constraints
	 * (the SNOMED paths, which OR everything) or read the versions; a caller that evaluates the
	 * AND/OR structure must use {@link #getInclusionClauses()}.
	 */
	public Map<FHIRCodeSystemVersion, ConjunctionConstraints> getInclusionConstraints() {
		return merge(inclusionClauses);
	}

	public Map<FHIRCodeSystemVersion, List<ConjunctionConstraints>> getInclusionClauses() {
		return inclusionClauses;
	}

	public Map<FHIRCodeSystemVersion, List<ConjunctionConstraints>> getExclusionClauses() {
		return exclusionClauses;
	}

	private static Map<FHIRCodeSystemVersion, ConjunctionConstraints> merge(Map<FHIRCodeSystemVersion, List<ConjunctionConstraints>> clauses) {
		Map<FHIRCodeSystemVersion, ConjunctionConstraints> merged = new HashMap<>();
		clauses.forEach((version, list) -> {
			ConjunctionConstraints all = new ConjunctionConstraints();
			list.forEach(clause -> clause.getDisjunctionConstraints().forEach(d -> all.addDisjunctionConstraints(d.getConstraints())));
			merged.put(version, all);
		});
		return merged;
	}

	public Set<CodeSelectionCriteria> getNestedSelections() {
		return nestedSelections;
	}

	/** As {@link #getInclusionConstraints()}, for exclude clauses. */
	public Map<FHIRCodeSystemVersion, ConjunctionConstraints> getExclusionConstraints() {
		return merge(exclusionClauses);
	}

	private Set<FHIRCodeSystemVersion> doGatherAllInclusionVersions(Set<FHIRCodeSystemVersion> versions) {
		versions.addAll(inclusionClauses.keySet());
		for (CodeSelectionCriteria nestedSelection : nestedSelections) {
			nestedSelection.doGatherAllInclusionVersions(versions);
		}
		return versions;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		CodeSelectionCriteria that = (CodeSelectionCriteria) o;
		return Objects.equals(valueSetUserRef, that.valueSetUserRef) && Objects.equals(inclusionClauses, that.inclusionClauses) && Objects.equals(nestedSelections, that.nestedSelections) && Objects.equals(exclusionClauses, that.exclusionClauses);
	}

	@Override
	public int hashCode() {
		return Objects.hash(valueSetUserRef, inclusionClauses, nestedSelections, exclusionClauses);
	}
}
