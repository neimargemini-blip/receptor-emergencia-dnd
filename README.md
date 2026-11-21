# 🛡️ Receptor & Emissor de SOS de Emergência (com Bypass de Não Perturbe)

Aplicativo Android nativo de código aberto desenvolvido em **Kotlin**, **Jetpack Compose (Material 3)** e **Coroutines/Flow**.

Projetado para garantir que chamadas telefônicas e alertas de socorro com localização GPS de contatos de emergência pré-autorizados toquem no aparelho mesmo que o telefone esteja bloqueado ou no modo **Não Perturbe (DND)**, com proteção 24/7 e auto-reinício automático caso o aplicativo seja fechado.

---

## 🚀 Principais Funcionalidades

### 1. 🚨 Receptor de Emergência (Bypass de Não Perturbe)
- **Bypass do Modo DND:** Toca som em volume de emergência (50% ou máximo configurado) mesmo com o celular em modo silencioso ou Não Perturbe.
- **Sirene de Emergência Crítica:** Alarme sonoro contínuo com frequências harmônicas de alta audibilidade (`critical_alarm.wav`).
- **Anúncio por Voz (TTS Nativo):** O assistente fala repetidamente em português o nome do contato que está ligando ("Chamada de emergência de [Nome]") até que a ligação seja atendida ou encerrada.
- **Chamadas Telefônicas & SMS de Socorro:** Detecta chamadas recebidas e SMS com coordenadas GPS de contatos autorizados.
- **Integração Intuitiva com Google Maps:** Botão dedicado e de 1 toque **"ABRIR NO GOOGLE MAPS"** na tela de alerta, na notificação e no histórico.

### 2. ⚡ Auto-Reinício se Fechado & Proteção 24/7 em Segundo Plano
- **Watchdog de Auto-Reinício (`onTaskRemoved` + `AlarmManager`):** Se o usuário deslizar o aplicativo na tela de recentes para fechá-lo, o Watchdog do sistema detecta e acorda o serviço de monitoramento automaticamente em 1.2 segundos.
- **Recuperação por Eventos do Sistema (`AutoRestartReceiver`):** O monitor se reativa automaticamente após a reinicialização do celular (`BOOT_COMPLETED`), desbloqueio de tela (`USER_PRESENT`) ou conexão ao carregador.
- **Painel de Configuração de Autostart:** Suporte nativo para abrir a tela de Início Automático de fabricantes como Xiaomi (MIUI/HyperOS), Samsung (One UI), Huawei, Oppo, Vivo, Motorola e Asus.
- **Orientação de Bateria Irrestrita:** Botão de 1 clique para isentar o app da otimização de bateria do Android, garantindo que o sistema nunca o suspenda em segundo plano.

### 3. 🆘 Emissor de SOS com Contagem Regressiva de Segurança
- **Contagem Regressiva de 10 Segundos:** Evita acionamentos acidentais permitindo cancelar o SOS antes de disparar.
- **Envio Automático de SMS com GPS:** Dispara as coordenadas exatas da localização via SMS para os contatos de emergência cadastrados.
- **Ligação em Viva-Voz:** Inicia automaticamente uma chamada telefônica com viva-voz ligado para o contato principal de socorro.

### 4. 👥 Gestão Segura de Contatos VIP
- Separação clara entre contatos que **Recebem SOS** e contatos autorizados a **Furar o Não Perturbe**.
- Importação direta da agenda do celular (`READ_CONTACTS`) ou cadastro manual com DDD.
- Ativação/desativação individual de cada contato.

### 5. 🧪 Painel de Simulação e Testes
- Botões integrados na aba de recebimento para simular chamadas e SMS de emergência com contagem regressiva de 5 segundos (tempo ideal para bloquear a tela, ativar o DND e testar o funcionamento real).
- Seletor de toque para personalizar ou usar o alarme padrão.

---

## 🔒 Permissões e Conformidade com as Políticas do GitHub

Este aplicativo é um utilitário de segurança e proteção pessoal para uso próprio e familiar.
- **100% Livre de Rastreamento Oculto ou Spyware:** O aplicativo possui interface gráfica completa, ícone visível na tela inicial, notificações persistentes em primeiro plano quando os serviços estão ativos e requer autorização explícita do usuário para cada permissão.
- **Sem Envio Oculto de Dados / Sem Servidores Terceiros:** O aplicativo não se comunica com servidores externos, não coleta dados nem realiza requisições de rede para telemetria ou rastreamento.
- **Código Aberto e Transparente:** Todas as permissões declaradas no `AndroidManifest.xml` (`RECEIVE_SMS`, `READ_PHONE_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_NOTIFICATION_POLICY`) são essenciais para o funcionamento local das rotinas de emergência.

---

## 📱 Requisitos do Sistema
- **Sistema Operacional:** Android 8.0 (Oreo / API 26) ou superior (Totalmente compatível com Android 14 e Android 15).
- **Compilador/SDK:** Android Gradle Plugin 8.7.2, Kotlin 2.0.21, Java 17.

---

## 🛠️ Como Compilar o Projeto

```bash
# Clonar o repositório
git clone https://github.com/neimargemini-blip/receptor-emergencia-dnd.git
cd receptor-emergencia-dnd

# Compilar versão Debug
./gradlew assembleDebug

# Compilar versão Release
./gradlew assembleRelease
```

---

⚠️ **Aviso Legal:** Este aplicativo é um complemento pessoal de apoio e **não substitui** os serviços públicos oficiais de emergência (**190 Polícia Militar**, **192 SAMU**, **193 Bombeiros**).
