import usePriceMatrix from "../features/price-matrix/usePriceMatrix";
import BrandPriceSheet from "../features/price-matrix/BrandPriceSheet";
import "../styles/price-matrix.css";

function PriceMatrixPage() {
    const { brands, modelsByBrand, planningByModel, drafts, expandedBrandIds, savingModelId, savedModelId,
        isLoading, errorMessage, modelCount, toggleBrand, updateDraft, saveModel } = usePriceMatrix();
    return (
        <section className="page">
            <header className="page-header price-matrix-page-header">
                <div>
                    <p className="page-eyebrow">Planowanie zakupów</p>
                    <h1 className="page-title">Cennik modeli</h1>
                    <p className="page-description">
                        Observer najpierw tworzy punkt startowy, a potem regularnie sprawdza
                        najnowsze oferty. „Dzisiaj” liczy od 00:00, „Ten tydzień” od
                        poniedziałku 00:00, a „Ostatni pełny tydzień” obejmuje poprzedni
                        poniedziałek–niedzielę. „Potrzebne boty” bazuje na pełnym poprzednim
                        tygodniu; dopóki go nie ma, używana jest estymacja ze średniej dziennej
                        z całego dostępnego okresu po baseline. Wydajność jednego bota nie jest
                        już stałą 5 rozmów dziennie — aplikacja wylicza ją z potwierdzonych
                        FIRST_OFFER z ostatnich 28 dni i uwzględnia także aktywne dodatkowe produkty.
                    </p>
                </div>

                <div className="price-matrix-summary">
                    <strong>{modelCount}</strong>
                    <span>modeli</span>
                </div>
            </header>

            {errorMessage !== null && (
                <div className="form-message form-message-error" role="alert">
                    {errorMessage}
                </div>
            )}

            {isLoading ? (
                <article className="content-card">
                    <div className="dictionary-list-state">Pobieranie cennika...</div>
                </article>
            ) : brands.length === 0 ? (
                <article className="content-card">
                    <div className="dictionary-list-state">
                        Najpierw dodaj marki i modele w słownikach.
                    </div>
                </article>
            ) : (
                <article className="content-card price-matrix-card">
                    <div className="price-matrix-board">
                        {brands.map((brand) => (
                            <BrandPriceSheet
                                key={brand.id}
                                brand={brand}
                                models={modelsByBrand[brand.id] ?? []}
                                planningByModel={planningByModel}
                                drafts={drafts}
                                expanded={expandedBrandIds.has(brand.id)}
                                savingModelId={savingModelId}
                                savedModelId={savedModelId}
                                onToggle={() => toggleBrand(brand.id)}
                                onDraftChange={updateDraft}
                                onSave={saveModel}
                            />
                        ))}
                    </div>
                </article>
            )}
        </section>
    );
}

export default PriceMatrixPage;
