import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { api } from '../api/client'
import type { FileNode, FileSearchHit } from '../api/contracts'
import { useWorkspaceStore } from './workspace'
import { errorMessage } from '../utils/unknown'

export type ExplorerDirectory = {
  entries: FileNode[]
  nextCursor?: string
  loading: boolean
}

export const useExplorerStore = defineStore('explorer', () => {
  const workspace = useWorkspaceStore()
  const directories = ref<Record<string, ExplorerDirectory>>({})
  const expandedPaths = ref<string[]>([])
  const selectedPath = ref('')
  const searchMode = ref<'name' | 'content'>('name')
  const searchQuery = ref('')
  const searchActive = ref(false)
  const searching = ref(false)
  const searchResults = ref<FileSearchHit[]>([])
  const searchNextCursor = ref<string>()
  const refreshing = ref(false)
  const error = ref<string | null>(null)
  const directorySequences = new Map<string, number>()
  let searchSequence = 0
  let refreshSequence = 0

  async function loadDirectory(target = '', append = false, preserveLoaded = false) {
    const requestEpoch = workspace.epoch
    const sequence = (directorySequences.get(target) || 0) + 1
    directorySequences.set(target, sequence)
    const current = directories.value[target]
    directories.value = {
      ...directories.value,
      [target]: {
        entries: current?.entries || [],
        nextCursor: current?.nextCursor,
        loading: true,
      },
    }
    if (!preserveLoaded) error.value = null
    try {
      let page = await api.files(target, append ? current?.nextCursor : undefined)
      if (requestEpoch !== workspace.epoch || sequence !== directorySequences.get(target)) return false
      const entries = [...page.entries]
      // Refresh all pages the user already loaded instead of collapsing the view
      // back to page one, which can hide expanded folders and selected files.
      const wanted = preserveLoaded ? current?.entries.length || 0 : 0
      while (page.nextCursor && entries.length < wanted) {
        page = await api.files(target, page.nextCursor)
        if (requestEpoch !== workspace.epoch || sequence !== directorySequences.get(target)) return false
        entries.push(...page.entries)
      }
      const existing = append ? current?.entries || [] : []
      directories.value = {
        ...directories.value,
        [target]: {
          entries: [...existing, ...entries],
          nextCursor: page.nextCursor,
          loading: false,
        },
      }
      return true
    } catch (cause: unknown) {
      if (requestEpoch === workspace.epoch && sequence === directorySequences.get(target)) {
        error.value = errorMessage(cause)
        const failed = directories.value[target]
        if (failed) failed.loading = false
      }
      return false
    }
  }

  async function refresh() {
    if (refreshing.value) return
    const epoch = workspace.epoch, sequence = ++refreshSequence
    refreshing.value = true; error.value = null
    const expanded = [...expandedPaths.value].sort((a, b) => a.split('/').length - b.split('/').length)
    // Closed branches reload when opened next. Discard their pending responses too.
    for (const path of Object.keys(directories.value)) {
      if (path && !expanded.includes(path)) {
        delete directories.value[path]
        directorySequences.set(path, (directorySequences.get(path) || 0) + 1)
      }
    }
    const current = () => epoch === workspace.epoch && sequence === refreshSequence
    const removed = new Set<string>()
    try {
      if (!await loadDirectory('', false, true) || !current()) return
      for (const path of expanded) {
        if (!current()) return
        let parent = '', missing = false
        for (const segment of path.split('/')) {
          const child = parent ? `${parent}/${segment}` : segment
          const listing = directories.value[parent]
          if (removed.has(parent) || listing && !listing.nextCursor
              && !listing.entries.some(entry => entry.path === child && entry.type === 'directory')) {
            missing = true; break
          }
          parent = child
        }
        if (missing) {
          removed.add(path); delete directories.value[path]
          directorySequences.set(path, (directorySequences.get(path) || 0) + 1)
          expandedPaths.value = expandedPaths.value.filter(item => item !== path)
          continue
        }
        await loadDirectory(path, false, true)
      }
      if (current() && searchActive.value && searchQuery.value.trim()) await search(false, true)
    } finally {
      if (current()) refreshing.value = false
    }
  }

  async function toggleDirectory(node: FileNode) {
    if (node.type !== 'directory') return
    if (expandedPaths.value.includes(node.path)) {
      expandedPaths.value = expandedPaths.value.filter(path => path !== node.path)
      return
    }
    expandedPaths.value = [...expandedPaths.value, node.path]
    if (!directories.value[node.path]) await loadDirectory(node.path)
  }

  async function search(append = false, preserveError = false) {
    const value = searchQuery.value.trim()
    if (!value) {
      clearSearch()
      return
    }
    const requestEpoch = workspace.epoch
    const sequence = ++searchSequence
    searching.value = true; searchActive.value = true
    if (!preserveError) error.value = null
    if (!append) { searchResults.value = []; searchNextCursor.value = undefined }
    try {
      const result = await api.searchFiles(value, searchMode.value === 'content', append ? searchNextCursor.value : undefined)
      if (requestEpoch !== workspace.epoch || sequence !== searchSequence) return
      searchResults.value = append ? [...searchResults.value, ...result.entries] : result.entries
      searchNextCursor.value = result.nextCursor
    } catch (cause: unknown) {
      if (requestEpoch === workspace.epoch && sequence === searchSequence) error.value = errorMessage(cause)
    } finally {
      if (requestEpoch === workspace.epoch && sequence === searchSequence) searching.value = false
    }
  }

  function showContentSearch() {
    searchMode.value = 'content'
  }

  function resetSearchResults() {
    searchSequence++; searchActive.value = false; searching.value = false
    searchResults.value = []
    searchNextCursor.value = undefined
  }

  function clearSearch() { searchQuery.value = ''; resetSearchResults() }

  watch([searchQuery, searchMode], resetSearchResults, {flush:'sync'})

  function reset() {
    refreshSequence++; refreshing.value = false
    directorySequences.clear()
    searchSequence++
    directories.value = {}
    expandedPaths.value = []
    selectedPath.value = ''
    searchMode.value = 'name'
    clearSearch()
    searchResults.value = []
    searchNextCursor.value = undefined
    error.value = null
  }

  watch(() => workspace.epoch, reset)

  return {
    directories, expandedPaths, selectedPath, searchMode, searchQuery, searchActive, searching,
    searchResults, searchNextCursor, refreshing, error,
    loadDirectory, toggleDirectory, search, showContentSearch,
    clearSearch, reset, refresh,
  }
})
