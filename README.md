# Nuvem — backup ilimitado de fotos e vídeos no seu Telegram

App Android (Kotlin + Jetpack Compose + TDLib) que usa a sua própria conta do Telegram como nuvem gratuita para fotos e vídeos, em qualidade original.

**Baixar:** veja a aba Releases.

## Primeiro uso
1. Abra https://my.telegram.org, entre com seu número (o código chega no app do Telegram).
2. Toque em "API development tools", crie um app (título "Nuvem", nome curto qualquer) e copie `api_id` e `api_hash`.
3. Cole no app, entre com seu número + código (+ senha de 2 etapas, se tiver).
4. Crie o grupo privado "Nuvem" (com tópicos). Se a conta for nova e o Telegram bloquear a criação, use Mensagens Salvas por enquanto.

## Recursos
- Backup automático (Wi-Fi/carregando), em segundo plano com WorkManager, com retomada
- Envio como foto e vídeo no Telegram (aparece e toca direto no app). Vídeos sobem no arquivo original, sem recompressão no celular. Se o Telegram recusar (tipo não suportado ou arquivo > 2 GB), cai para documento.
- Fotos em qualidade original (ligado por padrão): além da foto pra ver, envia o arquivo original como documento (a restauração usa o original). O Telegram comprime a foto de visualização (~2560 px).
- Organização por álbum (Camera, WhatsApp, Pictures…): envia um álbum por vez, em grupos de até 10 na ordem de data (foto e vídeo juntos; documentos em grupos separados), e no fim marca “📁 Álbum …”. Originais das fotos sobem no grupo de documentos seguinte, sem resposta.
- Grupo com tópicos (um tópico por álbum) e separação automática (prints, memes, documentos, vídeos longos, câmera)
- Legenda curta só com hashtags (`#2026_11 #camera`); metadados ficam no celular e num índice invisível no destino. Botão “Limpar legendas antigas” edita as que já tinham o JSON.
- Sem duplicadas (mesmo arquivo em pastas diferentes, pelo hash). Em Ajustes/Backup: **Apagar duplicadas do Telegram** (fotos, vídeos e originais).
- Backup instantâneo ao entrar mídia nova, upload em paralelo (até 3 tópicos) e retomável
- Resumo no fim (`✅ 120 fotos, 15 vídeos, 2,3 GB`)
- Liberar espaço automático (opcional, desligado por padrão, com confirmação)
- Restaurar um álbum inteiro nas pastas originais
- Canal/grupo secreto, pastas escolhidas, protegido por PIN ou digital
- Até 2 GB por arquivo (4 GB com Premium)
- Linha do tempo por data, álbuns por pasta, busca, visualizador com zoom e streaming de vídeo
- Restaurar para o celular, apagar da nuvem (com confirmação)
- "Liberar armazenamento": apaga do celular só o que foi verificado (hash + tamanho + mensagem existente no Telegram), com confirmação do sistema
- Sessão do Telegram salva no aparelho (não precisa redigitar o número ao reabrir). É possível cadastrar um e-mail de login; não existe entrada com conta Google.
