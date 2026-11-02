() => {
    const text = String(
        document.body?.innerText
            || document.documentElement?.innerText
            || ""
    ).toLowerCase();

    return text.includes(
        "access to this site is blocked for this computer"
    ) || text.includes(
        "twoja sesja została zablokowana"
    );
}
