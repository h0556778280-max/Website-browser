package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Bookmark
import com.example.data.BrowserDatabase
import com.example.data.BrowserRepository
import com.example.data.HistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLEncoder

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = BrowserConfig.INITIAL_URL,
    val title: String = if (BrowserConfig.INITIAL_URL == "about:blank") "מסך הבית" else "דף הבית"
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: BrowserRepository

    // Blocked URL state (used to show a friendly restriction warning)
    private val _blockedUrl = MutableStateFlow<String?>(null)
    val blockedUrl: StateFlow<String?> = _blockedUrl.asStateFlow()

    fun showBlockedPage(url: String) {
        _blockedUrl.value = url
        _isLoading.value = false
    }

    fun clearBlockedPage() {
        _blockedUrl.value = null
    }

    init {
        val database = BrowserDatabase.getDatabase(application)
        repository = BrowserRepository(database.browserDao())
    }

    val bookmarks: StateFlow<List<Bookmark>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryItem>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tabs Management
    private val _tabs = MutableStateFlow<List<BrowserTab>>(listOf(BrowserTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    // Current page load states for the active tab
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Flag to trigger WebView reload/load calls
    private val _navigationTrigger = MutableStateFlow<String?>(null)
    val navigationTrigger: StateFlow<String?> = _navigationTrigger.asStateFlow()

    fun clearNavigationTrigger() {
        _navigationTrigger.value = null
    }

    // Is the current active URL bookmarked?
    val currentTabUrl: StateFlow<String> = _activeTabIndex.flatMapLatest { index ->
        val currentTabs = _tabs.value
        if (index in currentTabs.indices) {
            flowOf(currentTabs[index].url)
        } else {
            flowOf(BrowserConfig.INITIAL_URL)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BrowserConfig.INITIAL_URL)

    val isCurrentUrlBookmarked: StateFlow<Boolean> = currentTabUrl.flatMapLatest { url ->
        if (url == "about:blank") {
            flowOf(false)
        } else {
            repository.isBookmarked(url)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadUrl(input: String) {
        var processedUrl = input.trim()
        if (processedUrl.isEmpty()) return

        // If it does not contain a space and has dot(s) or standard URL patterns, load it
        val isUrl = !processedUrl.contains(" ") && (
                processedUrl.startsWith("http://") || 
                processedUrl.startsWith("https://") || 
                processedUrl.startsWith("about:") || 
                processedUrl.contains(".")
        )

        if (isUrl) {
            if (!processedUrl.startsWith("http://") && !processedUrl.startsWith("https://") && !processedUrl.startsWith("about:")) {
                processedUrl = "https://$processedUrl"
            }
        } else {
            // Treat as Google search
            val encodedQuery = try {
                URLEncoder.encode(processedUrl, "UTF-8")
            } catch (e: Exception) {
                processedUrl
            }
            processedUrl = "https://www.google.com/search?q=$encodedQuery"
        }

        // Enforce allowed domains check
        if (!BrowserConfig.isUrlAllowed(processedUrl)) {
            showBlockedPage(processedUrl)
            return
        }

        clearBlockedPage()
        updateActiveTabUrl(processedUrl)
        _navigationTrigger.value = processedUrl
    }

    fun updateActiveTabUrl(url: String) {
        val index = _activeTabIndex.value
        val currentTabs = _tabs.value.toMutableList()
        if (index in currentTabs.indices) {
            val title = if (url == "about:blank") "מסך הבית" else currentTabs[index].title
            currentTabs[index] = currentTabs[index].copy(url = url, title = title)
            _tabs.value = currentTabs
            _searchQuery.value = if (url == "about:blank") "" else url
        }
    }

    fun updateActiveTabTitle(title: String) {
        val index = _activeTabIndex.value
        val currentTabs = _tabs.value.toMutableList()
        if (index in currentTabs.indices) {
            currentTabs[index] = currentTabs[index].copy(title = title)
            _tabs.value = currentTabs
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setLoadProgress(progress: Int) {
        _loadProgress.value = progress
    }

    // Bookmark actions
    fun toggleBookmark() {
        val url = currentTabUrl.value
        if (url == "about:blank" || url.isEmpty()) return

        viewModelScope.launch {
            if (isCurrentUrlBookmarked.value) {
                repository.deleteBookmarkByUrl(url)
            } else {
                val title = _tabs.value[_activeTabIndex.value].title
                repository.insertBookmark(Bookmark(title = title, url = url))
            }
        }
    }

    fun addBookmarkDirect(title: String, url: String) {
        viewModelScope.launch {
            repository.insertBookmark(Bookmark(title = title, url = url))
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.deleteBookmarkById(bookmark.id)
        }
    }

    // History actions
    fun addToHistory(title: String, url: String) {
        if (url == "about:blank" || url.isEmpty() || url.startsWith("data:")) return
        viewModelScope.launch {
            repository.insertHistoryItem(HistoryItem(title = title, url = url))
        }
    }

    fun deleteHistoryItem(item: HistoryItem) {
        viewModelScope.launch {
            repository.deleteHistoryById(item.id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // Multi-Tab management
    fun createNewTab(url: String = BrowserConfig.INITIAL_URL) {
        val currentTabs = _tabs.value.toMutableList()
        val newTab = BrowserTab(url = url)
        currentTabs.add(newTab)
        _tabs.value = currentTabs
        _activeTabIndex.value = currentTabs.size - 1
        _searchQuery.value = if (url == "about:blank") "" else url
        
        if (!BrowserConfig.isUrlAllowed(url)) {
            showBlockedPage(url)
        } else {
            clearBlockedPage()
            _navigationTrigger.value = url
        }
    }

    fun closeTab(index: Int) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size <= 1) {
            // If only one tab left, reset it to INITIAL_URL
            val defaultUrl = BrowserConfig.INITIAL_URL
            currentTabs[0] = BrowserTab(url = defaultUrl)
            _tabs.value = currentTabs
            _activeTabIndex.value = 0
            _searchQuery.value = if (defaultUrl == "about:blank") "" else defaultUrl
            
            if (!BrowserConfig.isUrlAllowed(defaultUrl)) {
                showBlockedPage(defaultUrl)
            } else {
                clearBlockedPage()
                _navigationTrigger.value = defaultUrl
            }
            return
        }

        currentTabs.removeAt(index)
        _tabs.value = currentTabs

        val activeIndex = _activeTabIndex.value
        if (activeIndex >= currentTabs.size) {
            _activeTabIndex.value = currentTabs.size - 1
        } else if (activeIndex > index) {
            _activeTabIndex.value = activeIndex - 1
        }
        
        val newActiveTab = _tabs.value[_activeTabIndex.value]
        _searchQuery.value = if (newActiveTab.url == "about:blank") "" else newActiveTab.url
        
        if (!BrowserConfig.isUrlAllowed(newActiveTab.url)) {
            showBlockedPage(newActiveTab.url)
        } else {
            clearBlockedPage()
            _navigationTrigger.value = newActiveTab.url
        }
    }

    fun selectTab(index: Int) {
        if (index in _tabs.value.indices) {
            _activeTabIndex.value = index
            val url = _tabs.value[index].url
            _searchQuery.value = if (url == "about:blank") "" else url
            
            if (!BrowserConfig.isUrlAllowed(url)) {
                showBlockedPage(url)
            } else {
                clearBlockedPage()
                _navigationTrigger.value = url
            }
        }
    }
}

class BrowserViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BrowserViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BrowserViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
