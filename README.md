# Nuvem — backup ilimitado de fotos e vídeos no seu Telegram

App Android (Kotlin + Jetpack Compose + TDLib) que usa a sua própria conta do Telegram como nuvem gratuita para fotos e vídeos, em qualidade original.

**Baixar:** veja a aba Releases.

## Primeiro uso
1. Abra https://my.telegram.org, entre com seu número (o código chega no app do Telegram).
2. Toque em "API development tools", crie um app (título "Nuvem", nome curto qualquer) e copie `api_id` e `api_hash`.
3. Cole no app, entre com seu número + código (+ senha de 2 etapas, se tiver).
4. Crie o canal privado "Nuvem" (ou use Mensagens Salvas) e escolha as pastas para backup.

## Recursos
- Backup automático (Wi-Fi/carregando), em segundo plano com WorkManager, com retomada
- Envio como documento (sem compressão), até 2 GB (4 GB com Premium)
- Metadados (SHA-256, tamanho, data, caminho) na legenda evitam duplicados
- Linha do tempo por data, álbuns por pasta, busca, visualizador com zoom e streaming de vídeo
- Restaurar para o celular, apagar da nuvem (com confirmação)
- "Liberar armazenamento": apaga do celular só o que foi verificado (hash + tamanho + mensagem existente no Telegram), com confirmação do sistema
