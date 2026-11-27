.class public final Lcom/android/server/pm/OneOSStorageHelper;
.super Ljava/lang/Object;
.source "OneOSStorageHelper.java"


# static fields
.field private static final SOCK_PATH:Ljava/lang/String; = "/data/system/oneos_storage.sock"

.field private static final TAG:Ljava/lang/String; = "OneOSStorage"


# direct methods
.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method private static appendJoined(Ljava/lang/StringBuilder;[Ljava/lang/String;C)V
    .locals 5

    if-nez p1, :cond_0

    return-void

    :cond_0
    const/4 v0, 0x1

    array-length v1, p1

    const/4 v2, 0x0

    :goto_0
    if-ge v2, v1, :cond_3

    aget-object v3, p1, v2

    if-eqz v3, :cond_2

    invoke-virtual {v3}, Ljava/lang/String;->isEmpty()Z

    move-result v4

    if-nez v4, :cond_2

    if-nez v0, :cond_1

    invoke-virtual {p0, p2}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    :cond_1
    invoke-virtual {p0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const/4 v0, 0x0

    :cond_2
    add-int/lit8 v2, v2, 0x1

    goto :goto_0

    :cond_3
    return-void
.end method

.method public static clearAppCache(Lcom/android/server/pm/pkg/AndroidPackage;I)V
    .locals 1

    const-string v0, "CLEAR_CACHE"

    invoke-static {v0, p0, p1}, Lcom/android/server/pm/OneOSStorageHelper;->sendClear(Ljava/lang/String;Lcom/android/server/pm/pkg/AndroidPackage;I)V

    return-void
.end method

.method public static clearAppData(Lcom/android/server/pm/pkg/AndroidPackage;I)V
    .locals 1

    const-string v0, "CLEAR_DATA"

    invoke-static {v0, p0, p1}, Lcom/android/server/pm/OneOSStorageHelper;->sendClear(Ljava/lang/String;Lcom/android/server/pm/pkg/AndroidPackage;I)V

    return-void
.end method

.method private static closeQuietly(Landroid/net/LocalSocket;)V
    .locals 0

    if-eqz p0, :cond_0

    :try_start_0
    invoke-virtual {p0}, Landroid/net/LocalSocket;->close()V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    :cond_0
    return-void
.end method

.method public static getAppSize(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;Landroid/content/pm/PackageStats;)Z
    .locals 3

    if-eqz p0, :cond_0

    const/4 v0, 0x0

    return v0

    :cond_0
    invoke-static/range {p0 .. p7}, Lcom/android/server/pm/OneOSStorageHelper;->getAppSizeAndFileCount(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;)[J

    move-result-object v0

    if-eqz v0, :cond_1

    if-eqz p8, :cond_1

    array-length v1, v0

    const/4 v2, 0x6

    if-lt v1, v2, :cond_1

    iget-wide v1, p8, Landroid/content/pm/PackageStats;->codeSize:J

    const/4 p0, 0x0

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p8, Landroid/content/pm/PackageStats;->codeSize:J

    iget-wide v1, p8, Landroid/content/pm/PackageStats;->dataSize:J

    const/4 p0, 0x1

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p8, Landroid/content/pm/PackageStats;->dataSize:J

    iget-wide v1, p8, Landroid/content/pm/PackageStats;->cacheSize:J

    const/4 p0, 0x2

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p8, Landroid/content/pm/PackageStats;->cacheSize:J

    iget-wide v1, p8, Landroid/content/pm/PackageStats;->externalCodeSize:J

    const/4 p0, 0x3

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p8, Landroid/content/pm/PackageStats;->externalCodeSize:J

    iget-wide v1, p8, Landroid/content/pm/PackageStats;->externalDataSize:J

    const/4 p0, 0x4

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p8, Landroid/content/pm/PackageStats;->externalDataSize:J

    iget-wide v1, p8, Landroid/content/pm/PackageStats;->externalCacheSize:J

    const/4 p0, 0x5

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p8, Landroid/content/pm/PackageStats;->externalCacheSize:J

    :cond_1
    const/4 v0, 0x1

    return v0
.end method

.method public static getAppSizeAndFileCount(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;)[J
    .locals 2

    if-eqz p0, :cond_0

    const/4 v0, 0x0

    return-object v0

    :cond_0
    new-instance v0, Ljava/lang/StringBuilder;

    const/16 v1, 0x80

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(I)V

    const-string v1, "APP_SIZE\t"

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    if-gez p2, :cond_1

    const/4 p2, 0x0

    :cond_1
    invoke-virtual {v0, p2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const/16 v1, 0x9

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    const/16 v1, 0x2c

    invoke-static {v0, p1, v1}, Lcom/android/server/pm/OneOSStorageHelper;->appendJoined(Ljava/lang/StringBuilder;[Ljava/lang/String;C)V

    const/16 v1, 0x9

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    const/16 v1, 0x7c

    invoke-static {v0, p7, v1}, Lcom/android/server/pm/OneOSStorageHelper;->appendJoined(Ljava/lang/StringBuilder;[Ljava/lang/String;C)V

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    const/16 v1, 0xc

    invoke-static {v0, v1}, Lcom/android/server/pm/OneOSStorageHelper;->queryLongs(Ljava/lang/String;I)[J

    move-result-object v0

    if-nez v0, :cond_2

    new-array v0, v1, [J

    :cond_2
    return-object v0
.end method

.method public static getExternalSize(Ljava/lang/String;II[I)[J
    .locals 2

    if-eqz p0, :cond_0

    const/4 v0, 0x0

    return-object v0

    :cond_0
    new-instance v0, Ljava/lang/StringBuilder;

    const/16 v1, 0x20

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(I)V

    const-string v1, "EXT_SIZE\t"

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    if-gez p1, :cond_1

    const/4 p1, 0x0

    :cond_1
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    const/4 v1, 0x6

    invoke-static {v0, v1}, Lcom/android/server/pm/OneOSStorageHelper;->queryLongs(Ljava/lang/String;I)[J

    move-result-object v0

    if-nez v0, :cond_2

    new-array v0, v1, [J

    :cond_2
    return-object v0
.end method

.method public static getUserSize(Ljava/lang/String;II[I[ILandroid/content/pm/PackageStats;)Z
    .locals 3

    if-eqz p0, :cond_0

    const/4 v0, 0x0

    return v0

    :cond_0
    new-instance v0, Ljava/lang/StringBuilder;

    const/16 v1, 0x20

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(I)V

    const-string v1, "USER_SIZE\t"

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    if-gez p1, :cond_1

    const/4 p1, 0x0

    :cond_1
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    const/4 v1, 0x6

    invoke-static {v0, v1}, Lcom/android/server/pm/OneOSStorageHelper;->queryLongs(Ljava/lang/String;I)[J

    move-result-object v0

    if-eqz v0, :cond_2

    if-eqz p5, :cond_2

    array-length v2, v0

    if-lt v2, v1, :cond_2

    iget-wide v1, p5, Landroid/content/pm/PackageStats;->codeSize:J

    const/4 p0, 0x0

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p5, Landroid/content/pm/PackageStats;->codeSize:J

    iget-wide v1, p5, Landroid/content/pm/PackageStats;->dataSize:J

    const/4 p0, 0x1

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p5, Landroid/content/pm/PackageStats;->dataSize:J

    iget-wide v1, p5, Landroid/content/pm/PackageStats;->cacheSize:J

    const/4 p0, 0x2

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p5, Landroid/content/pm/PackageStats;->cacheSize:J

    iget-wide v1, p5, Landroid/content/pm/PackageStats;->externalCodeSize:J

    const/4 p0, 0x3

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p5, Landroid/content/pm/PackageStats;->externalCodeSize:J

    iget-wide v1, p5, Landroid/content/pm/PackageStats;->externalDataSize:J

    const/4 p0, 0x4

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p5, Landroid/content/pm/PackageStats;->externalDataSize:J

    iget-wide v1, p5, Landroid/content/pm/PackageStats;->externalCacheSize:J

    const/4 p0, 0x5

    aget-wide p0, v0, p0

    add-long/2addr v1, p0

    iput-wide v1, p5, Landroid/content/pm/PackageStats;->externalCacheSize:J

    :cond_2
    const/4 v0, 0x1

    return v0
.end method

.method private static queryLongs(Ljava/lang/String;I)[J
    .locals 5

    invoke-static {p0}, Lcom/android/server/pm/OneOSStorageHelper;->transact(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    const/4 v1, 0x0

    if-eqz v0, :cond_2

    const-string v2, "OK "

    invoke-virtual {v0, v2}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v2

    if-nez v2, :cond_0

    goto :goto_2

    :cond_0
    const/4 v2, 0x3

    invoke-virtual {v0, v2}, Ljava/lang/String;->substring(I)Ljava/lang/String;

    move-result-object v0

    const-string v2, " "

    invoke-virtual {v0, v2}, Ljava/lang/String;->split(Ljava/lang/String;)[Ljava/lang/String;

    move-result-object v0

    array-length v2, v0

    if-ge v2, p1, :cond_1

    return-object v1

    :cond_1
    new-array v1, p1, [J

    const/4 v2, 0x0

    :goto_0
    if-ge v2, p1, :cond_2

    :try_start_0
    aget-object v3, v0, v2

    invoke-static {v3}, Ljava/lang/Long;->parseLong(Ljava/lang/String;)J

    move-result-wide v3

    aput-wide v3, v1, v2
    :try_end_0
    .catch Ljava/lang/NumberFormatException; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_1

    :catch_0
    const-wide/16 v3, 0x0

    aput-wide v3, v1, v2

    :goto_1
    add-int/lit8 v2, v2, 0x1

    goto :goto_0

    :cond_2
    :goto_2
    return-object v1
.end method

.method private static sendClear(Ljava/lang/String;Lcom/android/server/pm/pkg/AndroidPackage;I)V
    .locals 2

    if-nez p1, :cond_0

    return-void

    :cond_0
    invoke-interface {p1}, Lcom/android/server/pm/pkg/AndroidPackage;->getPackageName()Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :cond_3

    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z

    move-result v1

    if-eqz v1, :cond_1

    goto :goto_0

    :cond_1
    if-gez p2, :cond_2

    const/4 p2, 0x0

    :cond_2
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const/16 p0, 0x9

    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v1, p2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/android/server/pm/OneOSStorageHelper;->transact(Ljava/lang/String;)Ljava/lang/String;

    :cond_3
    :goto_0
    return-void
.end method

.method private static transact(Ljava/lang/String;)Ljava/lang/String;
    .locals 5

    new-instance v0, Landroid/net/LocalSocket;

    invoke-direct {v0}, Landroid/net/LocalSocket;-><init>()V

    :try_start_0
    new-instance v1, Landroid/net/LocalSocketAddress;

    const-string v2, "/data/system/oneos_storage.sock"

    sget-object v3, Landroid/net/LocalSocketAddress$Namespace;->FILESYSTEM:Landroid/net/LocalSocketAddress$Namespace;

    invoke-direct {v1, v2, v3}, Landroid/net/LocalSocketAddress;-><init>(Ljava/lang/String;Landroid/net/LocalSocketAddress$Namespace;)V

    invoke-virtual {v0, v1}, Landroid/net/LocalSocket;->connect(Landroid/net/LocalSocketAddress;)V

    const/16 v1, 0x1388

    invoke-virtual {v0, v1}, Landroid/net/LocalSocket;->setSoTimeout(I)V

    invoke-virtual {v0}, Landroid/net/LocalSocket;->getOutputStream()Ljava/io/OutputStream;

    move-result-object v1

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const/16 v3, 0xa

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    sget-object v3, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;

    invoke-virtual {v2, v3}, Ljava/lang/String;->getBytes(Ljava/nio/charset/Charset;)[B

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/io/OutputStream;->write([B)V

    invoke-virtual {v1}, Ljava/io/OutputStream;->flush()V

    new-instance v1, Ljava/io/BufferedReader;

    new-instance v2, Ljava/io/InputStreamReader;

    invoke-virtual {v0}, Landroid/net/LocalSocket;->getInputStream()Ljava/io/InputStream;

    move-result-object v4

    invoke-direct {v2, v4, v3}, Ljava/io/InputStreamReader;-><init>(Ljava/io/InputStream;Ljava/nio/charset/Charset;)V

    invoke-direct {v1, v2}, Ljava/io/BufferedReader;-><init>(Ljava/io/Reader;)V

    invoke-virtual {v1}, Ljava/io/BufferedReader;->readLine()Ljava/lang/String;

    move-result-object v1
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    invoke-static {v0}, Lcom/android/server/pm/OneOSStorageHelper;->closeQuietly(Landroid/net/LocalSocket;)V

    return-object v1

    :catchall_0
    move-exception v1

    goto :goto_0

    :catch_0
    move-exception v1

    :try_start_1
    const-string v2, "OneOSStorage"

    new-instance v3, Ljava/lang/StringBuilder;

    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V

    const-string v4, "oneos_storaged request failed: "

    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v3, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v3

    invoke-static {v2, v3, v1}, Landroid/util/Slog;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    invoke-static {v0}, Lcom/android/server/pm/OneOSStorageHelper;->closeQuietly(Landroid/net/LocalSocket;)V

    const/4 v0, 0x0

    return-object v0

    :goto_0
    invoke-static {v0}, Lcom/android/server/pm/OneOSStorageHelper;->closeQuietly(Landroid/net/LocalSocket;)V

    throw v1
.end method
