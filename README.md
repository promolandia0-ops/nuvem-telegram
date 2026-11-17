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
- Organização **só pelas pastas da galeria do celular** (Camera, Telegram, WhatsApp Video, Screenshots, Screen recordings…). Sem categorias como “vídeos longos”, prints ou memes. Uma pasta inteira de cada vez, em grupos de até 10 na ordem de data; no fim marca “📁 Álbum …”. Começa a enviar a pasta assim que o primeiro grupo de 10 está pronto, e já lê a próxima por trás.
- Grupo com tópicos (um tópico por pasta do celular). Hashtag curta só com mês e nome da pasta (`#2026_11 #camera`).
- Legenda curta só com hashtags (`#2026_11 #camera`); metadados ficam no celular e num índice invisível no destino. Botão “Limpar legendas antigas” edita as que já tinham o JSON.
- Sem duplicadas (mesmo arquivo em pastas diferentes, pelo hash). Em Ajustes/Backup: **Apagar duplicadas do Telegram** (fotos, vídeos e originais).
- Backup instantâneo ao entrar mídia nova, envio pasta por pasta (sem misturar) e retomável. Hash rápido (1 MB no começo e no fim) com cache por arquivo.
- Resumo no fim (`✅ 120 fotos, 15 vídeos, 2,3 GB`)
- Liberar espaço automático (opcional, desligado por padrão, com confirmação)
- Restaurar um álbum inteiro nas pastas originais
- Canal/grupo secreto, pastas escolhidas, protegido por PIN ou digital
- Até 2 GB por arquivo (4 GB com Premium)
- Linha do tempo por data, álbuns por pasta, busca, visualizador de fotos com zoom
- Player de vídeo no próprio app (streaming pelo Telegram, começa a tocar enquanto baixa, com indicador de carregamento). Controles: rotação, brilho/volume por gesto, toque duplo ±6 s, zoom, travar tela, velocidade 0,25×–4×, repetir/A-B, anterior/próximo, retomar posição, PiP, áudio em segundo plano, temporizador, faixa de áudio, captura de quadro, detalhes, compartilhar, baixar e apagar.
- Restaurar para o celular, apagar da nuvem (com confirmação)
- Ao abrir o app e antes de mostrar “Liberar armazenamento”, confere no Telegram o que ainda existe. Se você apagou ou limpou o histórico, some da galeria e do card de liberar espaço, e o próximo backup reenvia o que ainda estiver no celular. Antes de apagar do aparelho, confere de novo cada mensagem.
- Sessão do Telegram salva no aparelho (não precisa redigitar o número ao reabrir). É possível cadastrar um e-mail de login; não existe entrada com conta Google.
