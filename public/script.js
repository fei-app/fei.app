document.addEventListener('DOMContentLoaded', () => {
    const REPO_OWNER = 'etapaapp';
    const REPO_NAME = 'schooltests';
    const API_BASE = `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/contents`;

    const ENCODED_TOKEN = 'Z2l0aHViX3BhdF8xMUJVUVZJQ1kwR3BBRlplbWQ5c0xMX3cyYUE3WTlpWVE0a1c0WU0zaVlXQkJJOUFpTXZnOEpaOGQ4TUlucE9FNzNEUTIzNVFZVHlUWkxXQmNl';
    let GITHUB_TOKEN = '';

    try {
        GITHUB_TOKEN = atob(ENCODED_TOKEN);
    } catch (e) {
        console.warn('Falha ao decodificar token');
        GITHUB_TOKEN = '';
    }

    let currentPath = '';
    let allItems = [];
    let filteredItems = [];
    let isLoading = false;
    let currentSearchTerm = '';

    const itemsContainer = document.getElementById('itemsContainer');
    const searchInput = document.getElementById('searchInput');
    const breadcrumbNav = document.getElementById('breadcrumb');
    const loadingArea = document.getElementById('loadingArea');
    const errorArea = document.getElementById('errorArea');
    const errorMessageSpan = document.getElementById('errorMessage');
    const retryButton = document.getElementById('retryButton');
    const downloadAllBtn = document.getElementById('downloadAllBtn');

    // ---------- Utilitários ----------
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
        if (!showRetry) {
            retryButton.style.display = 'none';
        } else {
            retryButton.style.display = 'inline-flex';
        }
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

    // ---------- API ----------
    async function fetchGitHubContents(path) {
        let url = API_BASE;
        if (path && path.trim() !== '') {
            url += `/${encodeURIComponent(path)}`;
        }

        const headers = {
            'Accept': 'application/vnd.github.v3+json',
            'User-Agent': 'ProvasWebApp'
        };
        if (GITHUB_TOKEN) {
            headers['Authorization'] = `token ${GITHUB_TOKEN}`;
        }

        const response = await fetch(url, { headers });
        if (!response.ok) {
            let errorMsg = `Erro ${response.status}`;
            if (response.status === 401) errorMsg = 'Token inválido ou sem permissão.';
            else if (response.status === 404) errorMsg = 'Pasta não encontrada.';
            else if (response.status === 403) errorMsg = 'Limite de requisições excedido ou acesso negado.';
            throw new Error(errorMsg);
        }

        const data = await response.json();
        if (!Array.isArray(data)) {
            return [data];
        }
        return data;
    }

    // ---------- Download do repositório completo (ZIP client-side) ----------
    async function downloadAllProvas() {
        if (!hasInternet()) {
            showError('Sem conexão com a internet. Não é possível baixar o repositório.', true);
            return;
        }

        if (!GITHUB_TOKEN) {
            showError('Token não disponível. Verifique a configuração.', true);
            return;
        }

        // Desabilita o botão durante o processo
        downloadAllBtn.disabled = true;
        const originalText = downloadAllBtn.innerHTML;
        downloadAllBtn.innerHTML = '<i class="fas fa-spinner fa-pulse"></i> Coletando arquivos...';

        try {
            // 1. Listar todos os arquivos do repositório (recursivamente)
            const allFiles = await listAllFiles('');

            if (allFiles.length === 0) {
                throw new Error('Nenhum arquivo encontrado no repositório.');
            }

            downloadAllBtn.innerHTML = `<i class="fas fa-spinner fa-pulse"></i> Baixando ${allFiles.length} arquivos...`;

            // 2. Criar um ZIP usando JSZip
            const zip = new JSZip();

            // 3. Para cada arquivo, baixar o conteúdo e adicionar ao ZIP
            let completed = 0;
            for (const file of allFiles) {
                try {
                    const contentBlob = await downloadFileContent(file.download_url);
                    // Adiciona ao zip mantendo a estrutura de pastas (path relativo)
                    zip.file(file.path, contentBlob);
                    completed++;
                    // Atualiza o texto do botão a cada 10 arquivos (opcional)
                    if (completed % 10 === 0) {
                        downloadAllBtn.innerHTML = `<i class="fas fa-spinner fa-pulse"></i> ${completed}/${allFiles.length} arquivos...`;
                    }
                } catch (err) {
                    console.warn(`Erro ao baixar ${file.path}:`, err);
                    // Continua com os demais
                }
            }

            downloadAllBtn.innerHTML = '<i class="fas fa-spinner fa-pulse"></i> Compactando ZIP...';

            // 4. Gerar o blob final e disparar download
            const zipBlob = await zip.generateAsync({ type: 'blob' });
            const link = document.createElement('a');
            const url = URL.createObjectURL(zipBlob);
            link.href = url;
            link.download = `${REPO_NAME}-completo.zip`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(url);

            downloadAllBtn.innerHTML = '<i class="fas fa-check"></i> Download concluído!';
            setTimeout(() => {
                downloadAllBtn.innerHTML = originalText;
                downloadAllBtn.disabled = false;
            }, 3000);
        } catch (err) {
            console.error('Erro no download completo:', err);
            showError(`Falha ao baixar ZIP: ${err.message}. Tente novamente mais tarde.`, true);
            downloadAllBtn.innerHTML = originalText;
            downloadAllBtn.disabled = false;
        }
    }

    // Função recursiva para listar todos os arquivos (não pastas) do repositório
    async function listAllFiles(path) {
        const items = await fetchGitHubContents(path);
        let files = [];

        for (const item of items) {
            if (item.type === 'dir') {
                const subFiles = await listAllFiles(item.path);
                files.push(...subFiles);
            } else {
                // Garantir que o objeto tenha download_url (arquivo)
                if (item.download_url) {
                    files.push({
                        path: item.path,
                        name: item.name,
                        download_url: item.download_url
                    });
                }
            }
        }
        return files;
    }

    // Baixa o conteúdo de um arquivo via fetch com token
    async function downloadFileContent(downloadUrl) {
        const headers = {
            'Authorization': `token ${GITHUB_TOKEN}`,
            'User-Agent': 'ProvasWebApp'
        };
        const response = await fetch(downloadUrl, { headers });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status} ao baixar ${downloadUrl}`);
        }
        const blob = await response.blob();
        return blob;
    }

    // ---------- Renderização, navegação e filtro (mesmo código anterior) ----------
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
            card.setAttribute('data-type', item.type);
            card.setAttribute('data-path', item.path);
            card.setAttribute('data-name', item.name);
            if (!isDir && item.download_url) {
                card.setAttribute('data-download-url', item.download_url);
            }

            const iconDiv = document.createElement('div');
            iconDiv.className = 'item-icon';
            if (isDir) {
                iconDiv.innerHTML = '<i class="fas fa-folder fa-fw"></i>';
            } else {
                const ext = item.name.split('.').pop().toLowerCase();
                if (['pdf', 'doc', 'docx'].includes(ext)) iconDiv.innerHTML = '<i class="fas fa-file-pdf fa-fw"></i>';
                else if (['jpg', 'jpeg', 'png', 'gif'].includes(ext)) iconDiv.innerHTML = '<i class="fas fa-file-image fa-fw"></i>';
                else if (['zip', 'rar', '7z'].includes(ext)) iconDiv.innerHTML = '<i class="fas fa-file-archive fa-fw"></i>';
                else iconDiv.innerHTML = '<i class="fas fa-file-alt fa-fw"></i>';
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
                } else {
                    if (item.download_url) {
                        downloadFile(item.download_url, item.name);
                    } else {
                        const rawUrl = `https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/main/${item.path}`;
                        downloadFile(rawUrl, item.name);
                    }
                }
            });

            itemsContainer.appendChild(card);
        }
    }

async function downloadFile(url, filename) {
    if (!GITHUB_TOKEN) {
        // Fallback: tenta abrir diretamente (pode falhar se não estiver logado)
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', filename);
        link.setAttribute('target', '_blank');
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => {
            window.open(url, '_blank');
        }, 200);
        return;
    }

    try {
        const headers = {
            'Authorization': `token ${GITHUB_TOKEN}`,
            'User-Agent': 'ProvasWebApp'
        };
        const response = await fetch(url, { headers });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
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
        console.error('Erro no download do arquivo:', err);
        // Último recurso: tentar abrir diretamente
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', filename);
        link.setAttribute('target', '_blank');
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => {
            window.open(url, '_blank');
        }, 200);
    }
}

    function applyFilter() {
        const term = currentSearchTerm.trim().toLowerCase();
        if (!term) {
            filteredItems = [...allItems];
        } else {
            filteredItems = allItems.filter(item => item.name.toLowerCase().includes(term));
        }
        renderItems(filteredItems);
    }

    function onSearchInput() {
        currentSearchTerm = searchInput.value;
        applyFilter();
    }

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
            const items = await fetchGitHubContents(currentPath);
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

    function updateBreadcrumb() {
        breadcrumbNav.innerHTML = '';
        const parts = currentPath.split('/').filter(p => p !== '');

        const rootSpan = document.createElement('span');
        rootSpan.className = 'breadcrumb-root';
        rootSpan.innerHTML = '<i class="fas fa-folder-open"></i> Raiz';
        rootSpan.addEventListener('click', () => {
            if (!isLoading) {
                currentPath = '';
                loadContents();
            }
        });
        breadcrumbNav.appendChild(rootSpan);

        if (parts.length === 0) return;

        let accumulatedPath = '';
        for (let i = 0; i < parts.length; i++) {
            const part = parts[i];
            accumulatedPath += (accumulatedPath ? '/' : '') + part;
            const separator = document.createElement('span');
            separator.className = 'separator';
            separator.innerHTML = '<i class="fas fa-chevron-right"></i>';
            breadcrumbNav.appendChild(separator);

            const crumb = document.createElement('span');
            crumb.className = 'breadcrumb-item';
            crumb.innerHTML = `<i class="fas fa-folder"></i> ${escapeHtml(part)}`;
            crumb.addEventListener('click', (e) => {
                e.preventDefault();
                if (!isLoading) {
                    currentPath = accumulatedPath;
                    loadContents();
                }
            });
            breadcrumbNav.appendChild(crumb);
        }
    }

    function escapeHtml(str) {
        return str.replace(/[&<>]/g, function(m) {
            if (m === '&') return '&amp;';
            if (m === '<') return '&lt;';
            if (m === '>') return '&gt;';
            return m;
        });
    }

    // ---------- Eventos iniciais ----------
    searchInput.addEventListener('input', onSearchInput);
    retryButton.addEventListener('click', () => {
        if (hasInternet()) {
            loadContents();
        } else {
            showError('Sem conexão. Ative a internet e tente novamente.', true);
        }
    });
    downloadAllBtn.addEventListener('click', downloadAllProvas);

    if (hasInternet()) {
        loadContents().catch(console.warn);
    } else {
        showError('Sem conexão com a internet. Conecte-se e clique em tentar novamente.', true);
    }

    window.addEventListener('online', () => {
        if (!isLoading && (!itemsContainer.hasChildNodes() || allItems.length === 0)) {
            loadContents();
        } else {
            hideError();
            loadContents();
        }
    });
});
