#!/usr/bin/env node

const nodemailer = require('nodemailer');

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

async function main() {
  const raw = await readStdin();
  const payload = JSON.parse(raw || '{}');

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

  process.stdout.write(JSON.stringify({
    ok: true,
    messageId: info.messageId,
    response: info.response
  }));
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exit(1);
});
