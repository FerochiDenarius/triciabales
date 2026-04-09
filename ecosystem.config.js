module.exports = {
    apps: [{
        name: "tricia-bales-api",
        script: "java",
        args: "-jar target/baleshop-0.0.1-SNAPSHOT.jar",
        cwd: "/root/triciabales",
        env: {
            FRONTEND_URL: "https://www.yenkasa.xyz",
            SMTP_HOST: "smtp.zoho.com",
            SMTP_PORT: "587",
            SMTP_SECURE: "false",
            EMAIL_USER: "YOUR_EMAIL_USER",
            EMAIL_PASS: "YOUR_EMAIL_PASSWORD",
            EMAIL_FROM: "Yenkasa Store <YOUR_EMAIL>"
        }
    }]
};