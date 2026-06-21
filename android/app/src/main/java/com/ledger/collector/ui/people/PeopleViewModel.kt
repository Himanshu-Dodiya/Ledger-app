package com.ledger.collector.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.local.TagEntity
import com.ledger.collector.data.remote.GroupDto
import com.ledger.collector.data.remote.TimelineDto
import com.ledger.collector.data.repository.GroupsRepository
import com.ledger.collector.data.repository.PeopleRepository
import com.ledger.collector.data.repository.SplitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the People + Tags + Groups hub and the per-person timeline. */
class PeopleViewModel(
    private val repo: PeopleRepository,
    private val groupsRepo: GroupsRepository,
    private val splitRepo: SplitRepository,
) : ViewModel() {

    val tags: StateFlow<List<TagEntity>> =
        repo.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tag id selected as a filter, or null for "all". */
    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val people: StateFlow<List<PersonWithTags>> =
        combine(repo.people, _tagFilter, _query) { people, tag, q ->
            people.filter { p ->
                (tag == null || p.tags.any { it.id == tag }) &&
                    (q.isBlank() || p.person.name.contains(q, ignoreCase = true) ||
                        p.person.upiId?.contains(q, ignoreCase = true) == true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _groups = MutableStateFlow<List<GroupDto>>(emptyList())
    val groups: StateFlow<List<GroupDto>> = _groups.asStateFlow()

    private val _timeline = MutableStateFlow<TimelineDto?>(null)
    val timeline: StateFlow<TimelineDto?> = _timeline.asStateFlow()

    init { refresh(); loadGroups() }

    fun refresh() {
        viewModelScope.launch {
            repo.refresh().onFailure { _message.value = "Couldn't sync people." }
        }
    }

    fun loadGroups() {
        viewModelScope.launch { groupsRepo.list().onSuccess { _groups.value = it } }
    }

    fun saveGroup(id: String?, name: String, type: String?, memberIds: List<String>) {
        viewModelScope.launch {
            groupsRepo.save(id, name, type, memberIds)
                .onSuccess { loadGroups() }
                .onFailure { _message.value = "Couldn't save group." }
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            groupsRepo.delete(id).onSuccess { loadGroups() }.onFailure { _message.value = "Couldn't delete group." }
        }
    }

    fun loadTimeline(personId: String) {
        _timeline.value = null
        viewModelScope.launch { splitRepo.timeline(personId).onSuccess { _timeline.value = it } }
    }

    fun clearTimeline() { _timeline.value = null }

    fun setTagFilter(tagId: String?) { _tagFilter.value = tagId }
    fun setQuery(q: String) { _query.value = q }

    fun savePerson(id: String?, name: String, phone: String?, upiId: String?, tagIds: List<String>) {
        viewModelScope.launch {
            val result = if (id == null) repo.createPerson(name, phone, upiId, tagIds)
            else repo.updatePerson(id, name, phone, upiId, tagIds)
            result.onFailure { _message.value = "Couldn't save person: ${it.message ?: "unknown error"}" }
        }
    }

    fun deletePerson(id: String) {
        viewModelScope.launch { repo.deletePerson(id).onFailure { _message.value = "Couldn't delete." } }
    }

    fun saveTag(id: String?, name: String, color: String) {
        viewModelScope.launch {
            val result = if (id == null) repo.createTag(name, color) else repo.updateTag(id, name, color)
            result.onFailure { _message.value = "Couldn't save tag." }
        }
    }

    fun deleteTag(id: String) {
        viewModelScope.launch { repo.deleteTag(id).onFailure { _message.value = "Couldn't delete tag." } }
    }

    /** Create a tag inline (e.g. while editing a person) and return it so the caller can select it. */
    suspend fun addTag(name: String, color: String): TagEntity? =
        repo.createTagReturning(name, color)
            .onFailure { _message.value = "Couldn't create tag: ${it.message ?: "unknown error"}" }
            .getOrNull()

    fun clearMessage() { _message.value = null }
}
