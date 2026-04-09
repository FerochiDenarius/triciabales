module.exports = {
    apps: [
        {
            name: "tricia-bales-api",
            script: "java",
            args: "-jar target/baleshop-0.0.1-SNAPSHOT.jar",
            cwd: "/root/triciabales",
            env: {
                FRONTEND_URL: "https://www.yenkasa.xyz",
                SMTP_HOST: "smtp.zoho.com",
                SMTP_PORT: "587",
                SMTP_SECURE: "false",
                EMAIL_USER: "no.reply@yenkasa.xyz",
                EMAIL_PASS: "@Yenkasa01@$$",
                EMAIL_FROM: "Yenkasa Store <no.reply@yenkasa.xyz>"
            }
        }
    ]
};