package br.geracaoai.nuvem.shizuku;

interface IFileService {
    void destroy() = 16777114;
    /** Lista arquivos recursivamente: cada linha "tamanho|mtime|caminho". */
    List<String> list(String root, long minSize) = 1;
    ParcelFileDescriptor open(String path) = 2;
    boolean remove(String path) = 3;
    /** Busca paginada: arquivos >= minSize com extensão em exts (csv; vazio = qualquer), pulando pastas de cache/miniatura. "tamanho|mtime|caminho". */
    List<String> scan(String root, long minSize, String exts, int offset, int limit) = 4;
    /** Desinstala um app (pm uninstall). Devolve a saída do comando ("Success" quando deu certo). */
    String uninstall(String pkg) = 5;
    /** Tamanho total de uma pasta (bytes), inclusive Android/data. */
    long du(String path) = 6;
}
