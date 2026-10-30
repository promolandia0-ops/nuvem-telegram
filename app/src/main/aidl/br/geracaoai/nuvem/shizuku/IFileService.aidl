package br.geracaoai.nuvem.shizuku;

interface IFileService {
    void destroy() = 16777114;
    /** Lista arquivos recursivamente: cada linha "tamanho|mtime|caminho". */
    List<String> list(String root, long minSize) = 1;
    ParcelFileDescriptor open(String path) = 2;
    boolean remove(String path) = 3;
}
