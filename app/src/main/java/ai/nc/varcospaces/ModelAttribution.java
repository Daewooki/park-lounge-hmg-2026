package ai.nc.varcospaces;

/** Attribution for this specific 3D asset only; it does not license any sound. */
final class ModelAttribution {
    enum Publication { PLANNED, ORIGINAL, OPTIMIZED }

    // Change only after the corresponding original or optimized model is packaged.
    static final Publication PUBLICATION = Publication.OPTIMIZED;
    static final String TITLE = "Wooden Forest Gazebo";
    static final String AUTHOR = "u533671002";
    static final String SERVICE = "VARCO3D · varco-3d-2.0";
    static final String SOURCE = "https://3d.varco.ai/myAssets/d281210f-1267-4fc4-b85d-9d6e90fd1782";
    static final String LICENSE = "Creative Commons Attribution 4.0 International (CC BY 4.0)";
    static final String LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/";

    static String status(boolean english) {
        if (PUBLICATION == Publication.PLANNED)
            return english ? "Prepared for inclusion; not yet packaged in this app."
                : "앱 연결을 준비 중인 모델이며, 아직 앱에 포함되지 않았습니다.";
        return english ? "3D model included in this app." : "이 앱에 포함된 3D 모델입니다.";
    }

    static String changes(boolean english) {
        if (PUBLICATION == Publication.ORIGINAL)
            return english ? "No mesh or texture changes." : "메시·텍스처 변경 없음.";
        String changes = english ? "Mesh and texture optimization for in-vehicle display; added three inset water surfaces."
            : "차량 표시용 메시·텍스처 최적화 및 얕은 수면 3개 추가.";
        return PUBLICATION == Publication.PLANNED
            ? (english ? "Planned: " : "예정: ") + changes : changes;
    }

    private ModelAttribution() {}
}
