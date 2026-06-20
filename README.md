# Caesar

Caesar e' un semplice plugin Spigot/Paper per staff server Minecraft.

Il plugin salva alcune informazioni sui player quando entrano nel server, come IP e client usato, e permette allo staff di controllare possibili account alternativi.

## Comandi

- `/alts <player>` - mostra possibili alt account tramite IP
- `/check <player>` - mostra informazioni salvate su un player
- `/staffmode` o `/sm` - attiva o disattiva la staff mode

## Permessi

- `caesar.alts`
- `caesar.check`
- `caesar.staff`

## Installazione

Compila il plugin con Maven:

```bash
mvn clean package
```

Poi metti il file `.jar` generato nella cartella `plugins` del server e riavvia.
