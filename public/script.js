document.addEventListener('DOMContentLoaded', () => {

    // ─── CONFIGURAÇÃO ────────────────────────────────────────────────────────
    const PROJECT_PATH = 'etapa.app%2Fschooltests'; // URL-encoded: etapa.app/schooltests
    const REPO_NAME    = 'schooltests';
    const BRANCH       = 'main'; // troque para 'master' se necessário
    const API_BASE     = `https://gitlab.com/api/v4/projects/${PROJECT_PATH}/repository`;

    // Cole aqui o resultado de: btoa('glpat-SEUTOKENAQUI')
    const ENCODED_TOKEN = 'Z2xwYXQtbFRQT045UjhNa0x6NkV1VUNwZ3JJV002TVFwdk9qRUtkVHBzYm5aNmVBOC4wMS4xNzFhaTQzeGo=';
    let GITLAB_TOKEN = '';
    try {
        GITLAB_TOKEN = atob(ENCODED_TOKEN);
    } catch (e) {
        console.warn('Falha ao decodificar token');
    }
    // ─────────────────────────────────────────────────────────────────────────

    let currentPath      = '';
    let allItems         = [];
    let filteredItems    = [];
    let isLoading        = false;
    let currentSearchTerm = '';

    const itemsContainer   = document.getElementById('itemsContainer');
    const searchInput      = document.getElementById('searchInput');
    const breadcrumbNav    = document.getElementById('breadcrumb');
    const loadingArea      = document.getElementById('loadingArea');
    const errorArea        = document.getElementById('errorArea');
    const errorMessageSpan = document.getElementById('errorMessage');
    const retryButton      = document.getElementById('retryButton');
    const downloadAllBtn   = document.getElementById('downloadAllBtn');

    // ─── Utilitários ─────────────────────────────────────────────────────────

    function gitlabHeaders() {
        const h = { 'Content-Type': 'application/json' };
        if (GITLAB_TOKEN) h['PRIVATE-TOKEN'] = GITLAB_TOKEN;
        return h;
    }

    function showLoading(show) {
        isLoading = show;
        if (show) {
            loadingArea.style.display = 'flex';
            itemsContainer.style.display = 'none';
            errorArea.style.display = 'none';
        } else {
            loadingArea.style.display = 'none';
            itemsContainer.style.display = 'flex';
        }
    }

    function showError(message, showRetry = true) {
        errorMessageSpan.textContent = message || 'Erro ao carregar os dados.';
        errorArea.style.display = 'flex';
        retryButton.style.display = showRetry ? 'inline-flex' : 'none';
        itemsContainer.style.display = 'none';
        loadingArea.style.display = 'none';
    }

    function hideError() {
        errorArea.style.display = 'none';
        retryButton.style.display = 'inline-flex';
    }

    function hasInternet() {
        return navigator.onLine;
    }

    function escapeHtml(str) {
        return str.replace(/[&<>]/g, m =>
            m === '&' ? '&amp;' : m === '<' ? '&lt;' : '&gt;'
        );
    }

    // ─── API GitLab ───────────────────────────────────────────────────────────

    // Busca itens de um diretório com suporte a paginação automática
    async function fetchGitLabTree(path) {
        let allResults = [];
        let page = 1;
        const perPage = 100;

        while (true) {
            const encodedPath = path ? encodeURIComponent(path) : '';
            const params = new URLSearchParams({
                ref: BRANCH,
                per_page: perPage,
                page: page
            });
            if (path) params.set('path', path);

            const url = `${API_BASE}/tree?${params.toString()}`;
            const response = await fetch(url, { headers: gitlabHeaders() });

            if (!response.ok) {
                let errorMsg = `Erro ${response.status}`;
                if (response.status === 401) errorMsg = 'Token inválido ou sem permissão.';
                else if (response.status === 404) errorMsg = 'Pasta não encontrada.';
                else if (response.status === 403) errorMsg = 'Acesso negado.';
                throw new Error(errorMsg);
            }

            const data = await response.json();
            allResults.push(...data);

            // GitLab indica próxima página no header X-Next-Page
            const nextPage = response.headers.get('X-Next-Page');
            if (!nextPage || nextPage === '') break;
            page = parseInt(nextPage, 10);
        }

        // Normaliza para o formato que o resto do código espera
        // GitLab: type "tree" = pasta, "blob" = arquivo
        return allResults.map(item => ({
            name:         item.name,
            path:         item.path,
            type:         item.type === 'tree' ? 'dir' : 'file',
            download_url: item.type === 'blob'
                ? `${API_BASE}/files/${encodeURIComponent(item.path)}/raw?ref=${BRANCH}`
                : null
        }));
    }

    // ─── Download de arquivo individual ──────────────────────────────────────

    async function downloadFile(url, filename) {
        if (!GITLAB_TOKEN) {
            window.open(url, '_blank');
            return;
        }
        try {
            const response = await fetch(url, { headers: gitlabHeaders() });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const blob = await response.blob();
            const blobUrl = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = blobUrl;
            link.download = filename;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(blobUrl);
        } catch (err) {
            console.error('Erro no download:', err);
            window.open(url, '_blank');
        }
    }

    // ─── Download de todo o repositório como ZIP ──────────────────────────────
    // GitLab tem endpoint nativo de archive — muito mais eficiente que arquivo a arquivo

    async function downloadAllProvas() {
        if (!hasInternet()) {
            showError('Sem conexão com a internet.', true);
            return;
        }
        if (!GITLAB_TOKEN) {
            showError('Token não disponível. Verifique a configuração.', true);
            return;
        }

        downloadAllBtn.disabled = true;
        const originalHTML = downloadAllBtn.innerHTML;
        downloadAllBtn.innerHTML = '<i class="fas fa-spinner fa-pulse"></i> Preparando download...';

        try {
            const archiveUrl = `${API_BASE}/archive?sha=${BRANCH}&format=zip`;
            const response = await fetch(archiveUrl, { headers: gitlabHeaders() });

            if (!response.ok) {
                throw new Error(`Erro ${response.status} ao gerar arquivo ZIP.`);
            }

            downloadAllBtn.innerHTML = '<i class="fas fa-spinner fa-pulse"></i> Baixando ZIP...';

            const blob = await response.blob();
            const blobUrl = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = blobUrl;
            link.download = `${REPO_NAME}-completo.zip`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(blobUrl);

            downloadAllBtn.innerHTML = '<i class="fas fa-check"></i> Download concluído!';
            setTimeout(() => {
                downloadAllBtn.innerHTML = originalHTML;
                downloadAllBtn.disabled = false;
            }, 3000);

        } catch (err) {
            console.error('Erro no download completo:', err);
            showError(`Falha ao baixar ZIP: ${err.message}`, true);
            downloadAllBtn.innerHTML = originalHTML;
            downloadAllBtn.disabled = false;
        }
    }

    // ─── Renderização ─────────────────────────────────────────────────────────

    function renderItems(itemsToRender) {
        itemsContainer.innerHTML = '';

        if (!itemsToRender || itemsToRender.length === 0) {
            const emptyDiv = document.createElement('div');
            emptyDiv.className = 'empty-message';
            emptyDiv.innerHTML = `<i class="fas fa-folder-open"></i><br>Nenhum arquivo ou pasta encontrado.`;
            itemsContainer.appendChild(emptyDiv);
            return;
        }

        for (const item of itemsToRender) {
            const isDir = item.type === 'dir';

            const card = document.createElement('div');
            card.className = 'item-card';

            const iconDiv = document.createElement('div');
            iconDiv.className = 'item-icon';
            if (isDir) {
                iconDiv.innerHTML = '<i class="fas fa-folder fa-fw"></i>';
            } else {
                const ext = item.name.split('.').pop().toLowerCase();
                if (['pdf', 'doc', 'docx'].includes(ext))
                    iconDiv.innerHTML = '<i class="fas fa-file-pdf fa-fw"></i>';
                else if (['jpg', 'jpeg', 'png', 'gif'].includes(ext))
                    iconDiv.innerHTML = '<i class="fas fa-file-image fa-fw"></i>';
                else if (['zip', 'rar', '7z'].includes(ext))
                    iconDiv.innerHTML = '<i class="fas fa-file-archive fa-fw"></i>';
                else
                    iconDiv.innerHTML = '<i class="fas fa-file-alt fa-fw"></i>';
            }

            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'item-details';

            const nameSpan = document.createElement('div');
            nameSpan.className = 'item-name';
            nameSpan.textContent = item.name;

            const typeSpan = document.createElement('div');
            typeSpan.className = 'item-type';
            typeSpan.textContent = isDir ? 'Pasta' : 'Arquivo';

            detailsDiv.appendChild(nameSpan);
            detailsDiv.appendChild(typeSpan);
            card.appendChild(iconDiv);
            card.appendChild(detailsDiv);

            card.addEventListener('click', (e) => {
                e.stopPropagation();
                if (isDir) {
                    navigateToPath(item.path);
                } else if (item.download_url) {
                    downloadFile(item.download_url, item.name);
                }
            });

            itemsContainer.appendChild(card);
        }
    }

    // ─── Filtro de busca ──────────────────────────────────────────────────────

    function applyFilter() {
        const term = currentSearchTerm.trim().toLowerCase();
        filteredItems = term
            ? allItems.filter(item => item.name.toLowerCase().includes(term))
            : [...allItems];
        renderItems(filteredItems);
    }

    function onSearchInput() {
        currentSearchTerm = searchInput.value;
        applyFilter();
    }

    // ─── Navegação ────────────────────────────────────────────────────────────

    async function navigateToPath(newPath) {
        if (isLoading) return;
        currentPath = newPath;
        await loadContents();
    }

    async function loadContents() {
        if (!hasInternet()) {
            showError('Sem conexão com a internet. Verifique sua rede.', true);
            return;
        }

        showLoading(true);
        hideError();

        try {
            const items = await fetchGitLabTree(currentPath);
            items.sort((a, b) => {
                if (a.type === 'dir' && b.type !== 'dir') return -1;
                if (a.type !== 'dir' && b.type === 'dir') return 1;
                return a.name.localeCompare(b.name);
            });
            allItems = items;
            currentSearchTerm = searchInput.value.trim();
            applyFilter();
            updateBreadcrumb();
        } catch (err) {
            console.error('Erro ao carregar:', err);
            showError(err.message || 'Falha na requisição.', true);
            allItems = [];
            filteredItems = [];
            renderItems([]);
        } finally {
            showLoading(false);
        }
    }

    // ─── Breadcrumb ───────────────────────────────────────────────────────────

    function updateBreadcrumb() {
        breadcrumbNav.innerHTML = '';
        const parts = currentPath.split('/').filter(p => p !== '');

        const rootSpan = document.createElement('span');
        rootSpan.className = 'breadcrumb-root';
        rootSpan.innerHTML = '<i class="fas fa-folder-open"></i> Raiz';
        rootSpan.addEventListener('click', () => {
            if (!isLoading) { currentPath = ''; loadContents(); }
        });
        breadcrumbNav.appendChild(rootSpan);

        if (parts.length === 0) return;

        let accumulatedPath = '';
        for (const part of parts) {
            accumulatedPath += (accumulatedPath ? '/' : '') + part;

            const sep = document.createElement('span');
            sep.className = 'separator';
            sep.innerHTML = '<i class="fas fa-chevron-right"></i>';
            breadcrumbNav.appendChild(sep);

            const crumb = document.createElement('span');
            crumb.className = 'breadcrumb-item';
            crumb.innerHTML = `<i class="fas fa-folder"></i> ${escapeHtml(part)}`;
            const pathSnapshot = accumulatedPath;
            crumb.addEventListener('click', (e) => {
                e.preventDefault();
                if (!isLoading) { currentPath = pathSnapshot; loadContents(); }
            });
            breadcrumbNav.appendChild(crumb);
        }
    }

    // ─── Eventos ──────────────────────────────────────────────────────────────

    searchInput.addEventListener('input', onSearchInput);

    retryButton.addEventListener('click', () => {
        hasInternet()
            ? loadContents()
            : showError('Sem conexão. Ative a internet e tente novamente.', true);
    });

    downloadAllBtn.addEventListener('click', downloadAllProvas);

    window.addEventListener('online', () => {
        if (!isLoading && allItems.length === 0) loadContents();
    });

    if (hasInternet()) {
        loadContents().catch(console.warn);
    } else {
        showError('Sem conexão com a internet. Conecte-se e clique em tentar novamente.', true);
    }
});