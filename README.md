# AudioXtract

Aplicativo Android para extrair áudio e gerar legendas em SRT de vídeos. By Bonoco.

## Funções
- Extrai o áudio de arquivos de vídeo
- Gera legendas automáticas em português (.srt) a partir da fala do vídeo
- Formatos de áudio suportados: MP3, M4A (AAC), OPUS, OGG, WAV e FLAC
- Funciona offline. A geração de legenda usa o Whisper (modelo `ggml-base.bin`) rodando no próprio aparelho.

## Instalação
1. Vai em [Releases](../../releases) e baixa o `.apk` mais recente
2. Abre o arquivo no celular e permite a instalação de fontes desconhecidas
3. Pronto

## Compilar do código
1. Clona o repositório incluindo os submódulos:
   ```bash
   git clone --recurse-submodules https://github.com/bonoco/Audioxtract.git
   ```
2. Baixe o modelo `ggml-base.bin` em https://huggingface.co/ggerganov/whisper.cpp e coloque em `app/src/main/assets/models/ggml-base.bin`. O arquivo de modelo não vem no repositório.
3. Se desejar aplicar o patch do `jni.c` para Whisper em português (JNI token timestamps e prompt inicial), aplique o patch localizado em `patches/whisper-jni.patch`.
4. Abre a pasta no **Android Studio**.
5. Roda o Gradle sync e clica em **Run**.

## Licença e créditos
Este projeto está sob a licença [GNU GPL v3.0](LICENSE).

### Bibliotecas de terceiros
- **FFmpeg / FFmpegKit (variante `ffmpeg-kit-audio`)**: LGPL-3.0 / GPL-3.0. https://github.com/ffmpegkit-maintained/ffmpeg
- **whisper.cpp**: MIT License. https://github.com/ggml-org/whisper.cpp
- **Modelos Whisper da OpenAI**: MIT License. https://huggingface.co/ggerganov/whisper.cpp
- **AndroidX e Jetpack Compose**: Apache License 2.0.
- O FFmpeg é uma marca de seus respectivos mantenedores. Este projeto não é afiliado a eles.

Feito por Bonoco.
