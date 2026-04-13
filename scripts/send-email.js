#!/usr/bin/env node

const nodemailer = require('nodemailer');

const DEFAULT_RESEND_API_URL = 'https://api.resend.com/emails';
const DEFAULT_ZEPTOMAIL_API_URL = 'https://api.zeptomail.com/v1.1/email';

function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', chunk => {
      data += chunk;
    });
    process.stdin.on('end', () => resolve(data));
    process.stdin.on('error', reject);
  });
}

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required env var: ${name}`);
  }
  return value;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function parseSender(sender) {
  const raw = String(sender || '').trim();
  const match = raw.match(/^(.*)<([^>]+)>$/);

  if (!match) {
    return {
      address: raw,
      name: ''
    };
  }

  return {
    name: match[1].trim().replace(/^"|"$/g, ''),
    address: match[2].trim()
  };
}

function buildZeptoAuthorizationHeader() {
  const token = process.env.ZEPTOMAIL_SEND_MAIL_TOKEN;
  if (!token) {
    return null;
  }

  const trimmed = token.trim();
  return trimmed.toLowerCase().startsWith('zoho-enczapikey ')
    ? trimmed
    : `Zoho-enczapikey ${trimmed}`;
}

function buildHtml(subject, text, actionUrl) {
  const escapedSubject = escapeHtml(subject);
  const escapedText = escapeHtml(text).replace(/\n/g, '<br>');
  const escapedUrl = escapeHtml(actionUrl || '');

  if (!actionUrl) {
    return `<div style="font-family:Arial,sans-serif;line-height:1.6;color:#111">${escapedText}</div>`;
  }

  return `
    <div style="font-family:Arial,sans-serif;line-height:1.6;color:#111">
      <h2 style="margin:0 0 16px">${escapedSubject}</h2>
      <p>${escapedText}</p>
      <p style="margin:24px 0">
        <a href="${escapedUrl}" style="display:inline-block;padding:12px 18px;background:#111;color:#fff;text-decoration:none;border-radius:6px">Open Link</a>
      </p>
      <p>If the button does not work, use this link:</p>
      <p><a href="${escapedUrl}">${escapedUrl}</a></p>
    </div>
  `;
}

async function sendViaZeptoMail(payload) {
  const authorization = buildZeptoAuthorizationHeader();
  if (!authorization) {
    return null;
  }

  const sender = parseSender(payload.from || process.env.EMAIL_FROM || process.env.EMAIL_USER);
  const controller = new AbortController();
  const timeoutMs = Number(process.env.ZEPTOMAIL_TIMEOUT || 10000);
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(process.env.ZEPTOMAIL_API_URL || DEFAULT_ZEPTOMAIL_API_URL, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: authorization
      },
      body: JSON.stringify({
        from: {
          address: sender.address,
          name: sender.name || 'Yenkasa Store'
        },
        to: [
          {
            email_address: {
              address: payload.to
            }
          }
        ],
        subject: payload.subject,
        textbody: payload.text,
        htmlbody: buildHtml(payload.subject, payload.text, payload.actionUrl)
      }),
      signal: controller.signal
    });

    const body = await response.text();

    if (!response.ok) {
      throw new Error(`ZeptoMail API ${response.status}: ${body}`);
    }

    return {
      provider: 'zeptomail-api',
      status: response.status,
      response: body
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function sendViaResend(payload) {
  const apiKey = process.env.RESEND_API_KEY;
  if (!apiKey) {
    return null;
  }

  const controller = new AbortController();
  const timeoutMs = Number(process.env.RESEND_TIMEOUT || 10000);
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(process.env.RESEND_API_URL || DEFAULT_RESEND_API_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        from: payload.from || process.env.EMAIL_FROM || process.env.EMAIL_USER,
        to: [payload.to],
        subject: payload.subject,
        text: payload.text,
        html: buildHtml(payload.subject, payload.text, payload.actionUrl)
      }),
      signal: controller.signal
    });

    const body = await response.text();

    if (!response.ok) {
      throw new Error(`Resend API ${response.status}: ${body}`);
    }

    return {
      provider: 'resend-api',
      status: response.status,
      response: body
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function sendViaSmtp(payload) {
  const transporter = nodemailer.createTransport({
    host: requiredEnv('SMTP_HOST'),
    port: Number(process.env.SMTP_PORT || 587),
    secure: process.env.SMTP_SECURE === 'true',
    connectionTimeout: Number(process.env.SMTP_CONNECTION_TIMEOUT || 10000),
    greetingTimeout: Number(process.env.SMTP_GREETING_TIMEOUT || 10000),
    socketTimeout: Number(process.env.SMTP_SOCKET_TIMEOUT || 10000),
    auth: {
      user: requiredEnv('EMAIL_USER'),
      pass: requiredEnv('EMAIL_PASS')
    }
  });

  const info = await transporter.sendMail({
    from: payload.from || process.env.EMAIL_FROM || process.env.EMAIL_USER,
    to: payload.to,
    subject: payload.subject,
    text: payload.text,
    html: buildHtml(payload.subject, payload.text, payload.actionUrl)
  });

  return {
    provider: 'smtp',
    messageId: info.messageId,
    response: info.response
  };
}

async function main() {
  const raw = await readStdin();
  const payload = JSON.parse(raw || '{}');
  const result =
    await sendViaResend(payload) ||
    await sendViaZeptoMail(payload) ||
    await sendViaSmtp(payload);

  process.stdout.write(JSON.stringify({
    ok: true,
    ...result
  }));
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exit(1);
});
